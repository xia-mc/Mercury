package asia.lira.mercury.jit.dump;

import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaselineClassExporter {
    private BaselineClassExporter() {
    }

    public static ExportResult exportClasses(Path runDirectory) throws IOException {
        Path outputDirectory = runDirectory.resolve("mercury").resolve("dumped").resolve("classes");
        DumpUtils.recreateDirectory(outputDirectory);

        int exported = 0;
        for (Identifier id : BaselineCompiledFunctionRegistry.getInstance().ids()) {
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().getArtifact(id);
            if (artifact == null) {
                continue;
            }

            Path path = outputDirectory.resolve(id.getNamespace()).resolve(id.getPath() + ".class");
            Files.createDirectories(path.getParent());
            Files.write(path, artifact.classBytes());
            exported++;
        }

        return new ExportResult(outputDirectory, exported);
    }

    public record ExportResult(
            Path outputDirectory,
            int exportedCount
    ) {
    }
}
