package it.migration.workbench;

import it.migration.workbench.model.Preview.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

abstract class TestWorkspace {
    static {
        try {
            Path workspace = Path.of("target/test-work").toAbsolutePath();
            Files.createDirectories(workspace);
            // Solo i repository sintetici dei test: nessuna modifica alla configurazione Git dell'utente.
            String trusted = workspace.toString().replace('\\', '/') + "/*";
            Files.writeString(Path.of("target/test-gitconfig"), "[safe]\n\tdirectory = " + trusted +
                    "\n[user]\n\tname = Migration Tests\n\temail = migration-tests@example.invalid\n[commit]\n\tgpgsign = false\n[core]\n\tautocrlf = false\n");
        }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static final class WorkspaceDirectoryFactory implements TempDirFactory {
        @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
            Path parent = Path.of("target/test-work").toAbsolutePath();
            Files.createDirectories(parent);
            return Files.createTempDirectory(parent, "junit-");
        }
    }
    @TempDir(factory = WorkspaceDirectoryFactory.class) Path temporary;
    final MigrationService service = new MigrationService();
    static final Path RULES = Path.of(System.getProperty("workbench.test.rules", "examples/migration.yml")).toAbsolutePath();
    static final String RECIPE = "it.migration.demo.Configurazioni";
    static final String SIMPLE = "app:\n  endpoint: old-service\n  timeout: 10s\n  legacyEndpoint: legacy\ncredentials:\n  enabled: false\n";
    static final String EXPECTED = "app:\n  endpoint: new-service\n  timeout: 30s\ncredentials:\n  enabled: true\n";

    Path write(Path root, String relative, String text) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    Path fixture(String folder, boolean condition) throws IOException {
        Path root = temporary.resolve(folder);
        write(root, "config/application.yaml", "security:\n  enabled: " + condition + "\n");
        write(root, "deploy/Blue_42/values.yaml", SIMPLE);
        return root;
    }

    Request request(Path root) {
        return new Request(root.toString(), RULES.toString(), RECIPE, Map.of("migration.endpoint", "new-service"));
    }

    FileResult file(Report report, String path) {
        return report.files().stream().filter(f -> path.equals(f.beforePath()) || path.equals(f.afterPath())).findFirst().orElseThrow();
    }

    String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Risorsa assente: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}

