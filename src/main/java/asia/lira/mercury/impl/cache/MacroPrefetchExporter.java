package asia.lira.mercury.impl.cache;

import net.minecraft.util.Identifier;

import asia.lira.mercury.jit.dump.DumpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MacroPrefetchExporter {
    private MacroPrefetchExporter() {
    }

    public static ExportResult export(Path runDirectory) throws IOException {
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        Path root = runDirectory.resolve("mercury").resolve("dumped").resolve("prefetch");
        Path candidates = root.resolve("candidates");
        Path active = root.resolve("active");
        DumpUtils.recreateDirectory(root);
        Files.createDirectories(candidates);
        Files.createDirectories(active);

        int candidateCount = 0;
        int activeCount = 0;
        for (MacroPrefetchPlan plan : registry.candidatePlans().stream()
                .sorted(Comparator.comparing(p -> p.key().macroFunctionId().toString() + ":" + p.storagePathExpression()))
                .toList()) {
            writePlan(candidates, plan, registry.line(plan.id()));
            candidateCount++;
            MacroPrefetchLine line = registry.line(plan.id());
            if (line != null && line.isActive()) {
                writePlan(active, plan, line);
                activeCount++;
            }
        }

        List<String> statsLines = List.of(
                "hits=" + registry.stats().hits(),
                "misses=" + registry.stats().misses(),
                "promotions=" + registry.stats().promotions(),
                "evictions=" + registry.stats().evictions(),
                "fallbacks=" + registry.stats().fallbacks(),
                "candidates=" + candidateCount,
                "active=" + activeCount
        );
        Files.write(root.resolve("stats.txt"), statsLines, StandardCharsets.UTF_8);
        return new ExportResult(root, candidateCount, activeCount);
    }

    private static void writePlan(Path root, MacroPrefetchPlan plan, MacroPrefetchLine line) throws IOException {
        Identifier pseudoId = Identifier.of(plan.macroFunctionId().getNamespace(), plan.macroFunctionId().getPath() + "_" + plan.id());
        Path path = root.resolve(pseudoId.getNamespace()).resolve(pseudoId.getPath() + ".txt");
        Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("planId=" + plan.id());
        lines.add("owner=" + plan.ownerFunctionId() + " node=" + plan.nodeIndex() + " binding=" + plan.bindingId());
        lines.add("macro=" + plan.macroFunctionId());
        lines.add("storage=" + plan.storageId() + " path=" + plan.storagePathExpression());
        lines.add("args=" + plan.argumentNames());
        lines.add("observedFields=" + plan.observedFieldSources());
        lines.add("summary=" + plan.generatedMacroSummary());
        if (line != null) {
            lines.add("active=" + line.isActive() + " valid=" + line.isValid());
            lines.add("hits=" + line.hits() + " misses=" + line.misses());
            lines.add("macroCalls=" + line.recentMacroCalls() + " storeWrites=" + line.recentStoreWrites() + " storeThenCall=" + line.storeThenCallMatches());
            lines.add("lastInvalidation=" + line.lastInvalidationReason());
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public record ExportResult(
            Path outputDirectory,
            int candidateCount,
            int activeCount
    ) {
    }
}
