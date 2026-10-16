package it.migration.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.migration.workbench.model.Preview.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Prove del JAR distribuito, in processi separati e cartelle senza progetto Maven. */
class StandaloneIT extends TestWorkspace {
    private final GitClient git = new GitClient();

    record Execution(int code, String output) { }

    Execution cli(Path runner, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
                "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9", "-jar", runner.resolve("workbench.jar").toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(runner.toFile()).redirectErrorStream(true);
        builder.environment().remove("CLASSPATH");
        builder.environment().remove("MAVEN_HOME");
        builder.environment().remove("M2_HOME");
        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            try { process.getInputStream().transferTo(bytes); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Timeout del JAR: " + String.join(" ", args));
        reader.join();
        return new Execution(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
    }

    Path runner() throws Exception {
        Path runner = Files.createDirectory(temporary.resolve("standalone senza build"));
        Files.copy(Path.of(System.getProperty("workbench.jar")), runner.resolve("workbench.jar"));
        Files.copy(RULES, runner.resolve("migration.yml"));
        assertFalse(Files.exists(runner.resolve("pom.xml")));
        assertFalse(Files.exists(runner.resolve(".mvn")));
        return runner;
    }

    @Test void completeMigrationOnThreeRepositoriesThreeEnvironmentsAndLocalRemotes() throws Exception {
        Path runner = runner();
        String[] names = {"ordine-service", "pagamento-service", "rendiconto-batch"};
        String[] sources = {"develop", "release/2026.10", "main"};
        Path remotes = Files.createDirectory(temporary.resolve("remotes"));
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Path source = Files.createDirectory(temporary.resolve(names[i]));
            write(source, "config/application.yaml", "security:\n  enabled: true\n");
            for (String env : List.of("svil", "uat-eu", "prd2")) write(source, "deploy/" + env + "/values.yaml", SIMPLE);
            String helm = "metadata:\n  name: {{ .Release.Name }}\n";
            write(source, "helm/templates/deployment.yaml", helm);
            git.checked(source, "init", "-b", sources[i]);
            git.checked(source, "config", "core.hooksPath", temporary.resolve("no-hooks").toString());
            git.checked(source, "add", ".");
            git.checked(source, "commit", "-m", "Initial fixture");
            String initialCommit = git.snapshot(source).head();
            Map<String, String> initialFiles = WorkspaceFiles.inventory(source);
            Path remote = remotes.resolve(names[i] + ".git");
            git.checked(temporary, "init", "--bare", remote.toString());
            // Solo fixture: trasporto nativo locale, senza rete e senza shell MSYS.
            git.checked(source, "config", "protocol.ext.allow", "always");
            String localTransport = "ext::git receive-pack " + remote.toString().replace('\\', '/').replace("%", "%%").replace(" ", "% ");
            git.checked(source, "remote", "add", "origin", localTransport);

            Path worktree = temporary.resolve(names[i] + "-migration");
            String branch = "migration/configurazioni";
            Execution prepare = cli(runner, "prepare", "--repository", source.toString(), "--source", sources[i], "--branch", branch, "--worktree", worktree.toString());
            assertEquals(0, prepare.code(), prepare.output());
            Path reportPath = runner.resolve(names[i] + "-preview.json");
            Execution preview = cli(runner, "preview", "--root", worktree.toString(), "--rules", runner.resolve("migration.yml").toString(),
                    "--recipe", RECIPE, "--param", "migration.endpoint=new-service", "--report", reportPath.toString(), "--diff");
            assertEquals(0, preview.code(), preview.output());
            Report report = new ReportStore().read(reportPath);
            assertFalse(report.hasErrors(), report.diagnostics().toString());
            assertEquals(5, report.metrics().discoveredFiles());
            assertEquals(3, report.changedFiles());
            assertEquals(1, report.files().stream().filter(f -> f.status() == Status.SKIPPED).count());
            assertEquals(initialCommit, report.git().head());
            assertEquals(initialFiles, WorkspaceFiles.inventory(worktree), "La preview non deve scrivere.");

            Execution apply = cli(runner, "apply", "--report", reportPath.toString());
            assertEquals(0, apply.code(), apply.output());
            for (String env : List.of("svil", "uat-eu", "prd2")) assertEquals(EXPECTED, Files.readString(worktree.resolve("deploy/" + env + "/values.yaml")));
            assertEquals(helm, Files.readString(worktree.resolve("helm/templates/deployment.yaml")));
            Execution again = cli(runner, "preview", "--root", worktree.toString(), "--rules", runner.resolve("migration.yml").toString(),
                    "--recipe", RECIPE, "--param", "migration.endpoint=new-service", "--report", runner.resolve(names[i] + "-second.json").toString());
            assertEquals(0, again.code(), again.output());
            assertEquals(0, new ReportStore().read(runner.resolve(names[i] + "-second.json")).changedFiles());

            Execution commit = cli(runner, "commit", "--report", reportPath.toString(), "--message", "Migrazione configurazioni");
            assertEquals(0, commit.code(), commit.output());
            String sha = commit.output().strip();
            assertTrue(sha.matches("[0-9a-f]{40,64}"), commit.output());
            assertTrue(git.snapshot(worktree).status().isEmpty());
            Execution push = cli(runner, "push", "--root", worktree.toString(), "--remote", "origin", "--branch", branch, "--commit", sha);
            assertEquals(0, push.code(), push.output());
            assertEquals(sha, git.checked(remote, "rev-parse", "refs/heads/" + branch).strip());
            assertEquals(EXPECTED, git.checked(remote, "show", sha + ":deploy/uat-eu/values.yaml"));
            assertEquals(initialCommit, git.snapshot(source).head());
            assertEquals(sources[i], git.snapshot(source).branch());
            assertEquals(initialFiles, WorkspaceFiles.inventory(source), "Il checkout originale deve rimanere intatto.");
            evidence.add(Map.of("repository", names[i], "sourceBranch", sources[i], "migrationBranch", branch,
                    "files", report.metrics().discoveredFiles(), "changes", report.changedFiles(), "skipped", 1,
                    "metrics", report.metrics(), "commit", sha, "localPushVerified", true));
        }
        Path proof = Path.of("target/verification/standalone-results.json");
        Files.createDirectories(proof.getParent());
        Files.write(proof, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
    }

    @Test void packagedCatalogAndCliFailureCodesWorkWithoutBuildTools() throws Exception {
        Path runner = runner();
        Execution describe = cli(runner, "describe", "--rules", runner.resolve("migration.yml").toString(), "--recipe", RECIPE);
        assertEquals(0, describe.code(), describe.output());
        assertTrue(describe.output().contains("conditionFile"));
        assertTrue(describe.output().contains("newValue"));
        Path root = fixture("bad-input", true);
        write(root, "bad.yaml", "bad: [\n");
        Path report = runner.resolve("error.json");
        Execution preview = cli(runner, "preview", "--root", root.toString(), "--rules", runner.resolve("migration.yml").toString(), "--recipe", RECIPE, "--report", report.toString());
        assertEquals(2, preview.code(), preview.output());
        assertTrue(preview.output().contains("bad.yaml"));
        Execution apply = cli(runner, "apply", "--report", report.toString());
        assertEquals(1, apply.code(), apply.output());
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }
}
