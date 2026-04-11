package asia.lira.mercury.ir;

import asia.lira.mercury.jit.dump.DumpUtils;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FunctionIrExporter {
    private FunctionIrExporter() {
    }

    public static ExportResult exportParsed(FunctionIrRegistry registry, Path runDirectory) throws IOException {
        Path outputDirectory = runDirectory.resolve("mercury").resolve("dumped").resolve("parsed");
        DumpUtils.recreateDirectory(outputDirectory);

        int exported = 0;
        for (Identifier id : registry.getParsedIds()) {
            FunctionIrRegistry.ParsedFunctionIr functionIr = registry.getParsed(id).orElse(null);
            if (functionIr == null) {
                continue;
            }

            writeLines(outputDirectory, id, FunctionIrDumper.dumpParsed(functionIr));
            exported++;
        }

        return new ExportResult(outputDirectory, exported);
    }

    public static ExportResult exportSemantic(FunctionIrRegistry registry, Path runDirectory) throws IOException {
        Path outputDirectory = runDirectory.resolve("mercury").resolve("dumped").resolve("semantic");
        DumpUtils.recreateDirectory(outputDirectory);

        int exported = 0;
        for (Identifier id : registry.getSemanticIds()) {
            FunctionIrRegistry.SemanticFunctionIr functionIr = registry.getSemantic(id).orElse(null);
            if (functionIr == null) {
                continue;
            }

            writeLines(outputDirectory, id, FunctionIrDumper.dumpSemantic(functionIr));
            exported++;
        }

        return new ExportResult(outputDirectory, exported);
    }

    private static void writeLines(Path rootDirectory, Identifier id, List<String> lines) throws IOException {
        Path namespaceDirectory = rootDirectory.resolve(id.getNamespace());
        Path filePath = namespaceDirectory.resolve(id.getPath() + ".txt");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    public record ExportResult(
            Path outputDirectory,
            int exportedCount
    ) {
    }
}
