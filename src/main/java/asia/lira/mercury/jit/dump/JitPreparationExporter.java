package asia.lira.mercury.jit.dump;

import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class JitPreparationExporter {
    private JitPreparationExporter() {
    }

    public static ExportResult exportPrepared(Path runDirectory) throws IOException {
        JitPreparationRegistry registry = JitPreparationRegistry.getInstance();
        Path outputDirectory = runDirectory.resolve("mercury").resolve("dumped").resolve("prepared");
        DumpUtils.recreateDirectory(outputDirectory);

        int exported = 0;
        for (Identifier id : registry.objectiveRegistry().count() >= 0
                ? registryPreparedIds(registry)
                : List.<Identifier>of()) {
            JitPreparationRegistry.PreparedFunctionPlan functionPlan = registry.getPreparedFunction(id);
            if (functionPlan == null) {
                continue;
            }
            writeLines(outputDirectory, id, JitPreparationDumper.dumpPrepared(functionPlan, registry.slotRegistry()));
            exported++;
        }

        return new ExportResult(outputDirectory, exported);
    }

    private static List<Identifier> registryPreparedIds(JitPreparationRegistry registry) {
        return registry.preparedFunctionIds().stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    private static void writeLines(Path root, Identifier id, List<String> lines) throws IOException {
        Path path = root.resolve(id.getNamespace()).resolve(id.getPath() + ".txt");
        Files.createDirectories(path.getParent());
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public record ExportResult(
            Path outputDirectory,
            int exportedCount
    ) {
    }
}
