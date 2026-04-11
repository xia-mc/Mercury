package asia.lira.mercury.impl.cache;

import asia.lira.mercury.jit.dump.DumpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MacroOptimizationExporter {
    private MacroOptimizationExporter() {
    }

    public static ExportResult export(Path runDirectory) throws IOException {
        Path root = runDirectory.resolve("mercury").resolve("dumped").resolve("macro-optimization");
        Path profileRoot = root.resolve("macro-profile");
        Path specializationRoot = root.resolve("macro-specialization");
        Path tier2Root = root.resolve("tier2");
        DumpUtils.recreateDirectory(root);
        Files.createDirectories(profileRoot);
        Files.createDirectories(specializationRoot);
        Files.createDirectories(tier2Root);

        int profileCount = 0;
        for (Map.Entry<Integer, MacroCallsiteProfile> entry : RuntimeProfileRegistry.getInstance().macroProfiles().entrySet()) {
            List<String> lines = new ArrayList<>();
            MacroCallsiteProfile profile = entry.getValue();
            lines.add("planId=" + entry.getKey());
            lines.add("callsite=" + profile.key());
            lines.add("totalCalls=" + profile.totalCalls());
            lines.add("prefetchHits=" + profile.prefetchHits() + " prefetchMisses=" + profile.prefetchMisses());
            lines.add("guardHits=" + profile.guardHits() + " guardMisses=" + profile.guardMisses());
            lines.add("specializationUses=" + profile.specializationUses() + " fallbackUses=" + profile.fallbackUses());
            for (MacroArgumentValueProfile valueProfile : profile.fieldProfiles().values()) {
                lines.add("field " + valueProfile.argumentName() + " -> " + valueProfile.counts());
            }
            for (MacroSpecializationCandidate candidate : profile.specializationCandidates(4, false)) {
                lines.add("candidate guard=" + candidate.guardPlan().signature() + " dominance=" + candidate.dominanceRatio());
            }
            write(profileRoot, "profile_" + entry.getKey() + ".txt", lines);
            profileCount++;
        }

        int specializationCount = 0;
        for (Map.Entry<Integer, java.util.List<InstalledMacroSpecialization>> entry : MacroOptimizationCoordinator.getInstance().allInstalledVersions().entrySet()) {
            int index = 0;
            for (InstalledMacroSpecialization specialization : entry.getValue()) {
                List<String> lines = List.of(
                        "planId=" + entry.getKey(),
                        "callsite=" + specialization.candidate().callsiteKey(),
                        "guard=" + specialization.candidate().guardPlan().signature(),
                        "artifact=" + (specialization.artifact() == null ? "<procedure>" : specialization.artifact().internalClassName())
                );
                write(specializationRoot, "specialization_" + entry.getKey() + "_" + index + ".txt", lines);
                specializationCount++;
                index++;
            }
        }

        int tier2Count = 0;
        Set<net.minecraft.util.Identifier> tier2Functions = asia.lira.mercury.jit.registry.Tier2CompilationCoordinator.getInstance().installedFunctions();
        Set<net.minecraft.util.Identifier> functionIds = new LinkedHashSet<>(RuntimeProfileRegistry.getInstance().functionProfiles().keySet());
        functionIds.addAll(tier2Functions);
        for (net.minecraft.util.Identifier functionId : functionIds) {
            FunctionExecutionProfile profile = RuntimeProfileRegistry.getInstance().functionProfiles().get(functionId);
            boolean tier2Installed = tier2Functions.contains(functionId);
            List<String> lines = new ArrayList<>();
            lines.add("function=" + functionId);
            lines.add("executions=" + (profile == null ? "<retired>" : profile.executions()));
            lines.add("tier2Installed=" + tier2Installed);
            write(tier2Root, functionId.getNamespace() + "_" + functionId.getPath().replace('/', '_') + ".txt", lines);
            if (tier2Installed) {
                tier2Count++;
            }
        }

        return new ExportResult(root, profileCount, specializationCount, tier2Count);
    }

    private static void write(Path root, String fileName, List<String> lines) throws IOException {
        Files.write(root.resolve(fileName), lines, StandardCharsets.UTF_8);
    }

    public record ExportResult(Path outputDirectory, int profileCount, int specializationCount, int tier2Count) {
    }
}
