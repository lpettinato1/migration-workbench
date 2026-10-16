package it.migration.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.migration.workbench.model.Preview.GitSnapshot;
import it.migration.workbench.model.Preview.Report;
import it.migration.workbench.model.Preview.Status;
import it.migration.workbench.ui.LocalUiServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Finalizzazione reale via HTTP: il client approva un report server, non ne fornisce uno arbitrario. */
@Timeout(120)
class UiGitTest extends TestWorkspace {
    private static final List<String> CHANGED = List.of("deploy/Blue_42/values.yaml", "deploy/Preview-EU/values.yaml");
    private final GitClient git = new GitClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private record PreviewJob(String id, Report report) { }

    private LocalUiServer server() throws Exception {
        return LocalUiServer.start(Path.of("").toAbsolutePath(), 0);
    }

    private Path repository(String name) throws Exception {
        Path root = fixture(name, true);
        write(root, "deploy/Preview-EU/values.yaml", SIMPLE);
        write(root, "helm/templates/configmap.yaml", "metadata:\n  name: {{ .Release.Name }}\n");
        git.checked(root, "init", "-b", "main");
        git.checked(root, "config", "user.name", "Migration Tests");
        git.checked(root, "config", "user.email", "migration-tests@example.invalid");
        git.checked(root, "config", "commit.gpgsign", "false");
        git.checked(root, "config", "core.autocrlf", "false");
        git.checked(root, "config", "core.hooksPath", temporary.resolve("no-hooks").toString());
        git.checked(root, "add", ".");
        git.checked(root, "commit", "-m", "Initial fixture");
        return root;
    }

    private Path remote(Path repository, String name) throws Exception {
        Path remote = temporary.resolve(name + ".git");
        git.checked(temporary, "init", "--bare", remote.toString());
        // Solo il repository sintetico: trasporto nativo locale, nessun server o credenziale.
        git.checked(repository, "config", "protocol.ext.allow", "always");
        String transport = "ext::git receive-pack " + remote.toString().replace('\\', '/').replace("%", "%%").replace(" ", "% ");
        git.checked(repository, "remote", "add", "origin", transport);
        return remote;
    }

    private HttpResponse<String> post(LocalUiServer server, String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(30))
                .header("X-Workbench-Token", server.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postOk(LocalUiServer server, String path, Object body) throws Exception {
        HttpResponse<String> response = post(server, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode get(LocalUiServer server, String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(server.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(20)).header("X-Workbench-Token", server.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void rejected(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
                "Richiesto un rifiuto esplicito, ricevuto " + response.statusCode() + ": " + response.body());
        String message = json.readTree(response.body()).path("error").asText();
        assertFalse(message.isBlank(), response.body());
        assertFalse(message.contains("\tat "), response.body());
    }

    private PreviewJob preview(LocalUiServer server, Path root) throws Exception {
        HttpResponse<String> response = post(server, "/api/preview", Map.of("roots", List.of(root.toString()),
                "yaml", Files.readString(RULES), "recipe", RECIPE, "parameters", Map.of("migration.endpoint", "new-service")));
        assertEquals(202, response.statusCode(), response.body());
        String id = json.readTree(response.body()).path("id").asText();
        assertFalse(id.isBlank());
        JsonNode job = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            job = get(server, "/api/jobs/" + id);
            if (List.of("COMPLETED", "FAILED").contains(job.path("status").asText())) break;
            Thread.sleep(25);
        }
        assertNotNull(job);
        assertEquals("COMPLETED", job.path("status").asText(), job.toPrettyString());
        assertEquals(1, job.path("reports").size(), job.toPrettyString());
        Report report = json.treeToValue(job.path("reports").get(0), Report.class);
        assertFalse(report.hasErrors(), report.diagnostics().toString());
        assertEquals(2, report.changedFiles());
        for (String path : CHANGED) assertEquals(EXPECTED, file(report, path).after());
        return new PreviewJob(id, report);
    }

    private JsonNode operation(LocalUiServer server, PreviewJob job, Path root) throws Exception {
        JsonNode operations = get(server, "/api/jobs/" + job.id() + "/operations").path("operations");
        assertEquals(1, operations.size(), operations.toPrettyString());
        assertEquals(root.toRealPath().toString(), operations.get(0).path("root").asText());
        return operations.get(0);
    }

    private PreviewJob reviewed(LocalUiServer server, Path root) throws Exception {
        PreviewJob job = preview(server, root);
        JsonNode result = postOk(server, "/api/jobs/" + job.id() + "/review",
                Map.of("root", root.toString(), "reviewedPaths", CHANGED, "confirmed", true));
        assertEquals("REVIEWED", result.path("stage").asText(), result.toPrettyString());
        return job;
    }

    @Test void preparedWorktreeCanBeReviewedAppliedCommittedAndPushedWithoutChangingTheSource() throws Exception {
        Path source = repository("source con spazi");
        Path remote = remote(source, "remote-ui");
        GitSnapshot originalGit = git.snapshot(source);
        Map<String, String> originalFiles = WorkspaceFiles.inventory(source);
        Path target = temporary.resolve("worktree migrazione");
        String branch = "migration/configurazioni-ui";
        try (LocalUiServer server = server()) {
            JsonNode details = postOk(server, "/api/git/details", Map.of("root", source.toString()));
            assertEquals("main", details.path("git").path("branch").asText());
            assertTrue(details.path("branches").toString().contains("main"), details.toPrettyString());
            assertTrue(details.path("remotes").toString().contains("origin"), details.toPrettyString());
            JsonNode prepared = postOk(server, "/api/git/prepare", Map.of("repository", source.toString(), "source", "main",
                    "branch", branch, "worktree", target.toString(), "confirmed", true));
            assertEquals(target.toRealPath().toString(), prepared.path("root").asText());
            assertEquals(branch, git.snapshot(target).branch());
            assertEquals(originalGit, git.snapshot(source));

            PreviewJob job = preview(server, target);
            assertEquals(1, job.report().files().stream().filter(file -> file.status() == Status.SKIPPED).count());
            assertEquals(originalFiles, WorkspaceFiles.inventory(target));
            JsonNode initial = operation(server, job, target);
            assertEquals("PREVIEW", initial.path("stage").asText());
            assertEquals(2, initial.path("changedFiles").asInt());
            postOk(server, "/api/jobs/" + job.id() + "/review",
                    Map.of("root", target.toString(), "reviewedPaths", CHANGED, "confirmed", true));
            JsonNode applied = postOk(server, "/api/jobs/" + job.id() + "/apply", Map.of("root", target.toString(), "confirmed", true));
            assertEquals("APPLIED", applied.path("stage").asText());
            for (String path : CHANGED) assertEquals(EXPECTED, Files.readString(target.resolve(path)));
            Map<String, String> afterApply = WorkspaceFiles.inventory(target);
            HttpResponse<String> repeatedApply = post(server, "/api/jobs/" + job.id() + "/apply", Map.of("root", target.toString(), "confirmed", true));
            assertEquals(409, repeatedApply.statusCode(), repeatedApply.body());
            rejected(repeatedApply);
            assertEquals(afterApply, WorkspaceFiles.inventory(target));
            assertEquals("APPLIED", operation(server, job, target).path("stage").asText());

            JsonNode committed = postOk(server, "/api/jobs/" + job.id() + "/commit",
                    Map.of("root", target.toString(), "message", "Migrazione configurazioni via UI", "confirmed", true));
            assertEquals("COMMITTED", committed.path("stage").asText());
            String sha = committed.path("commit").asText();
            assertTrue(sha.matches("[0-9a-f]{40,64}"), committed.toPrettyString());
            assertEquals(sha, git.snapshot(target).head());
            assertTrue(git.snapshot(target).status().isEmpty());
            assertEquals("Migrazione configurazioni via UI", git.checked(target, "log", "-1", "--format=%s").strip());
            assertEquals(CHANGED, git.checked(target, "diff-tree", "--no-commit-id", "--name-only", "-r", sha).lines().sorted().toList());
            HttpResponse<String> repeatedCommit = post(server, "/api/jobs/" + job.id() + "/commit",
                    Map.of("root", target.toString(), "message", "Non deve creare un secondo commit", "confirmed", true));
            assertEquals(409, repeatedCommit.statusCode(), repeatedCommit.body());
            rejected(repeatedCommit);
            assertEquals(sha, git.snapshot(target).head());
            assertEquals("COMMITTED", operation(server, job, target).path("stage").asText());
            assertEquals(1, git.run(remote, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch), null).exitCode());

            JsonNode pushed = postOk(server, "/api/jobs/" + job.id() + "/push",
                    Map.of("root", target.toString(), "remote", "origin", "confirmed", true));
            assertEquals("PUSHED", pushed.path("stage").asText());
            assertEquals(sha, pushed.path("commit").asText());
            assertEquals(branch, pushed.path("branch").asText());
            assertEquals("origin", pushed.path("remote").asText());
            assertEquals("PUSHED", operation(server, job, target).path("stage").asText());
            assertEquals(sha, git.checked(remote, "rev-parse", "refs/heads/" + branch).strip());
            for (String path : CHANGED) assertEquals(EXPECTED, git.checked(remote, "show", sha + ":" + path));
        }
        assertEquals(originalGit, git.snapshot(source));
        assertEquals(originalFiles, WorkspaceFiles.inventory(source));
    }

    @Test void everyMutationNeedsConfirmationAndTheServerOwnedReviewedReport() throws Exception {
        Path root = repository("conferme");
        Path remote = remote(root, "conferme-remote");
        Path alien = fixture("root estranea", false);
        Map<String, String> original = WorkspaceFiles.inventory(root);
        Map<String, String> alienOriginal = WorkspaceFiles.inventory(alien);
        GitSnapshot initialGit = git.snapshot(root);
        try (LocalUiServer server = server()) {
            PreviewJob job = preview(server, root);
            String endpoint = "/api/jobs/" + job.id();
            for (String action : List.of("apply", "commit", "push")) {
                rejected(post(server, endpoint + "/" + action,
                        Map.of("root", root.toString(), "message", "Non ancora applicato", "remote", "origin", "confirmed", true)));
            }
            for (Map<String, Object> body : List.<Map<String, Object>>of(
                    Map.of("root", root.toString(), "reviewedPaths", CHANGED),
                    Map.of("root", root.toString(), "reviewedPaths", CHANGED, "confirmed", false),
                    Map.of("root", root.toString(), "reviewedPaths", List.of(CHANGED.getFirst()), "confirmed", true))) {
                rejected(post(server, endpoint + "/review", body));
            }
            assertEquals("PREVIEW", operation(server, job, root).path("stage").asText());
            Map<String, Object> forged = new LinkedHashMap<>();
            forged.put("root", alien.toString());
            forged.put("confirmed", true);
            forged.put("reviewedPaths", CHANGED);
            forged.put("report", job.report());
            rejected(post(server, endpoint + "/review", forged));
            rejected(post(server, endpoint + "/apply", forged));
            assertEquals(original, WorkspaceFiles.inventory(root));
            assertEquals(alienOriginal, WorkspaceFiles.inventory(alien));

            postOk(server, endpoint + "/review", Map.of("root", root.toString(), "reviewedPaths", CHANGED, "confirmed", true));
            // Il job adesso è applicabile: non basta che il payload contenga un report valido di un'altra root.
            rejected(post(server, endpoint + "/apply", forged));
            rejected(post(server, endpoint + "/apply", Map.of("root", root.toString())));
            rejected(post(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", false)));
            assertEquals(original, WorkspaceFiles.inventory(root));
            assertEquals(alienOriginal, WorkspaceFiles.inventory(alien));
            postOk(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", true));
            rejected(post(server, endpoint + "/commit", Map.of("root", root.toString(), "message", "Richiede conferma")));
            rejected(post(server, endpoint + "/commit", Map.of("root", root.toString(), "message", "Richiede conferma", "confirmed", false)));
            assertEquals(initialGit.head(), git.snapshot(root).head());
            assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
            postOk(server, endpoint + "/commit", Map.of("root", root.toString(), "message", "Confermato", "confirmed", true));
            rejected(post(server, endpoint + "/push", Map.of("root", root.toString(), "remote", "origin")));
            rejected(post(server, endpoint + "/push", Map.of("root", root.toString(), "remote", "origin", "confirmed", false)));
            assertEquals("COMMITTED", operation(server, job, root).path("stage").asText());
            assertTrue(git.checked(remote, "for-each-ref", "--format=%(refname)", "refs/heads").isBlank());
        }
        assertEquals(alienOriginal, WorkspaceFiles.inventory(alien));
    }

    @Test void prepareRejectsMissingConfirmationDirtyCheckoutAndRepeatedBranchWithoutSideEffects() throws Exception {
        Path source = repository("prepare controllato");
        Path target = temporary.resolve("nuovo worktree");
        String branch = "migration/preparazione";
        Map<String, Object> body = new LinkedHashMap<>(Map.of("repository", source.toString(), "source", "main",
                "branch", branch, "worktree", target.toString()));
        GitSnapshot initial = git.snapshot(source);
        try (LocalUiServer server = server()) {
            rejected(post(server, "/api/git/prepare", body));
            body.put("confirmed", false);
            rejected(post(server, "/api/git/prepare", body));
            assertFalse(Files.exists(target));
            assertEquals(initial, git.snapshot(source));
            write(source, "untracked.txt", "Contenuto estraneo da conservare");
            body.put("confirmed", true);
            rejected(post(server, "/api/git/prepare", body));
            assertFalse(Files.exists(target));
            assertEquals("Contenuto estraneo da conservare", Files.readString(source.resolve("untracked.txt")));
            assertEquals(1, git.run(source, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch), null).exitCode());
            Files.delete(source.resolve("untracked.txt"));
            postOk(server, "/api/git/prepare", body);
            Path second = temporary.resolve("worktree che non deve nascere");
            body.put("worktree", second.toString());
            rejected(post(server, "/api/git/prepare", body));
            assertFalse(Files.exists(second));
            assertEquals(initial, git.snapshot(source));
            assertEquals(branch, git.snapshot(target).branch());
        }
    }

    @Test void changingAReadOnlyConditionAfterReviewBlocksApplyWithoutOverwritingIt() throws Exception {
        Path root = repository("snapshot obsoleto");
        GitSnapshot initial = git.snapshot(root);
        try (LocalUiServer server = server()) {
            PreviewJob job = reviewed(server, root);
            String external = "security:\n  enabled: false\n# Cambiamento successivo alla review\n";
            write(root, "config/application.yaml", external);
            Map<String, String> changedOutside = WorkspaceFiles.inventory(root);
            rejected(post(server, "/api/jobs/" + job.id() + "/apply", Map.of("root", root.toString(), "confirmed", true)));
            assertEquals(changedOutside, WorkspaceFiles.inventory(root));
            assertEquals(external, Files.readString(root.resolve("config/application.yaml")));
            for (String path : CHANGED) assertEquals(SIMPLE, Files.readString(root.resolve(path)));
            assertEquals(initial.head(), git.snapshot(root).head());
            assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
        }
    }

    @Test void unrelatedOrAlteredOutputPreventsCommitAndLeavesTheIndexUntouched() throws Exception {
        Path root = repository("commit non coerente");
        try (LocalUiServer server = server()) {
            PreviewJob job = reviewed(server, root);
            String endpoint = "/api/jobs/" + job.id();
            postOk(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", true));
            write(root, "manuale.txt", "Non deve finire nel commit della migrazione");
            Map<String, Object> commit = Map.of("root", root.toString(), "message", "Vietato includere modifiche estranee", "confirmed", true);
            rejected(post(server, endpoint + "/commit", commit));
            assertEquals(job.report().git().head(), git.snapshot(root).head());
            assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
            assertEquals("Non deve finire nel commit della migrazione", Files.readString(root.resolve("manuale.txt")));
            Files.delete(root.resolve("manuale.txt"));
            String manualChange = EXPECTED + "manual: da non sovrascrivere\n";
            write(root, CHANGED.getFirst(), manualChange);
            rejected(post(server, endpoint + "/commit", commit));
            assertEquals(job.report().git().head(), git.snapshot(root).head());
            assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
            assertEquals(manualChange, Files.readString(root.resolve(CHANGED.getFirst())));
            assertEquals("APPLIED", operation(server, job, root).path("stage").asText());
        }
    }

    @Test void changingBranchOrShaAfterCommitBlocksPushBeforeTheRemoteIsTouched() throws Exception {
        for (String change : List.of("branch", "sha")) {
            Path root = repository("push cambiato " + change);
            Path remote = remote(root, "remote-non-toccato-" + change);
            try (LocalUiServer server = server()) {
                PreviewJob job = reviewed(server, root);
                String endpoint = "/api/jobs/" + job.id();
                postOk(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", true));
                JsonNode committed = postOk(server, endpoint + "/commit", Map.of("root", root.toString(), "message", "Da approvazione UI", "confirmed", true));
                String approved = committed.path("commit").asText();
                assertEquals(approved, git.snapshot(root).head());
                if (change.equals("branch")) git.checked(root, "checkout", "-b", "modifica-esterna");
                else git.checked(root, "commit", "--allow-empty", "-m", "Commit aggiunto fuori dal flusso UI");
                GitSnapshot external = git.snapshot(root);
                assertTrue(external.status().isEmpty());
                rejected(post(server, endpoint + "/push", Map.of("root", root.toString(), "remote", "origin", "confirmed", true)));
                assertEquals(external, git.snapshot(root));
                assertTrue(git.checked(remote, "for-each-ref", "--format=%(refname)", "refs/heads").isBlank());
                JsonNode state = operation(server, job, root);
                assertEquals("COMMITTED", state.path("stage").asText());
                assertEquals(approved, state.path("commit").asText());
            }
        }
    }

    @Test void operationPollingExposesAnInFlightCommitWithoutAllowingASecondMutation() throws Exception {
        Path root = repository("commit in corso");
        try (LocalUiServer server = server()) {
            PreviewJob job = reviewed(server, root);
            String endpoint = "/api/jobs/" + job.id();
            postOk(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", true));
            var commit = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return post(server, endpoint + "/commit", Map.of("root", root.toString(), "message", "Commit con polling", "confirmed", true)); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            JsonNode inFlight = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!commit.isDone() && System.nanoTime() < deadline) {
                inFlight = get(server, endpoint + "/operations");
                if (inFlight.path("busy").asBoolean()) break;
                Thread.sleep(20);
            }
            assertNotNull(inFlight);
            assertTrue(inFlight.path("busy").asBoolean(), "Il client deve distinguere uno stato ancora in mutazione: " + inFlight);
            assertEquals("APPLIED", inFlight.path("operations").get(0).path("stage").asText());
            assertEquals(409, post(server, endpoint + "/apply", Map.of("root", root.toString(), "confirmed", true)).statusCode());
            HttpResponse<String> completed = commit.get(25, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(200, completed.statusCode(), completed.body());
            JsonNode finalState = get(server, endpoint + "/operations");
            assertFalse(finalState.path("busy").asBoolean());
            assertEquals("COMMITTED", finalState.path("operations").get(0).path("stage").asText());
            assertEquals(2, Integer.parseInt(git.checked(root, "rev-list", "--count", "HEAD").strip()));
        }
    }
}
