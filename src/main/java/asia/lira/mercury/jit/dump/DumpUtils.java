package asia.lira.mercury.jit.dump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class DumpUtils {
    private DumpUtils() {
    }

    public static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            } catch (RuntimeException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            }
        }
        Files.createDirectories(directory);
    }
}
