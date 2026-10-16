package it.migration.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.migration.workbench.ui.LocalUiServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP reale verso il servizio locale, con risultati attesi indipendenti dal motore. */
@Timeout(90)
class UiServerTest extends TestWorkspace {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private LocalUiServer server() throws Exception {
        return LocalUiServer.start(Path.of("").toAbsolutePath(), 0);
    }

    private HttpResponse<String> exchange(LocalUiServer server, String method, String path, Object body,
                                          String token, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(server.baseUri().resolve(path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) request.header("X-Workbench-Token", token);
        if (origin != null) request.header("Origin", origin);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(LocalUiServer server, String path, Object body) throws Exception {
        return exchange(server, "POST", path, body, server.token(), null);
    }

    private JsonNode get(LocalUiServer server, String path) throws Exception {
        HttpResponse<String> response = exchange(server, "GET", path, null, server.token(), null);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode postOk(LocalUiServer server, String path, Object body) throws Exception {
        HttpResponse<String> response = post(server, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private String preview(LocalUiServer server, List<Path> roots, String yaml, String recipe,
                           Map<String, String> parameters) throws Exception {
        HttpResponse<String> response = post(server, "/api/preview", Map.of(
                "roots", roots.stream().map(Path::toString).toList(),
                "yaml", yaml, "recipe", recipe, "parameters", parameters));
        assertEquals(202, response.statusCode(), response.body());
        String id = json.readTree(response.body()).path("id").asText();
        assertFalse(id.isBlank(), response.body());
        return id;
    }

    private JsonNode finished(LocalUiServer server, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode job;
        do {
            job = get(server, "/api/jobs/" + id);
            String status = job.path("status").asText();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) return job;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("La preview HTTP non termina: " + job);
        return null;
    }

    private JsonNode file(JsonNode report, String path) {
        for (JsonNode file : report.path("files")) {
            if (path.equals(file.path("beforePath").asText()) || path.equals(file.path("afterPath").asText())) return file;
        }
        return fail("File assente dal report: " + path + " in " + report);
    }

    @Test void bootstrapRequiresThePerSessionToken() throws Exception {
        try (LocalUiServer server = server()) {
            assertEquals("127.0.0.1", server.baseUri().getHost());
            assertEquals(401, exchange(server, "GET", "/api/bootstrap", null, null, null).statusCode());
            assertEquals(401, exchange(server, "GET", "/api/bootstrap", null, "token-errato", null).statusCode());
            JsonNode bootstrap = get(server, "/api/bootstrap");
            assertFalse(bootstrap.path("toolVersion").asText().isBlank());
            assertFalse(bootstrap.path("engineVersion").asText().isBlank());
            assertEquals(Path.of("").toRealPath().toString(), bootstrap.path("baseDirectory").asText());
            assertFalse(bootstrap.has("exampleRecipe"));
            assertFalse(server.token().isBlank());
            assertTrue(server.launchUri().getFragment().contains(server.token()));
        }
    }

    @Test void untrustedOriginAndReboundHostAreRejectedEvenWithAValidToken() throws Exception {
        try (LocalUiServer server = server()) {
            HttpResponse<String> response = exchange(server, "POST", "/api/inspect",
                    Map.of("root", temporary.toString()), server.token(), "https://evil.example");
            assertEquals(403, response.statusCode(), response.body());
            assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            assertEquals(200, exchange(server, "GET", "/api/bootstrap", null, server.token(),
                    server.baseUri().toString().replaceAll("/$", "")).statusCode());
            // Un socket permette di provare Host senza disabilitare le protezioni di HttpClient.
            try (Socket socket = new Socket("127.0.0.1", server.baseUri().getPort())) {
                socket.setSoTimeout(5_000);
                String request = "GET /api/bootstrap HTTP/1.1\r\nHost: attacker.example\r\n" +
                        "X-Workbench-Token: " + server.token() + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String responseText = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(responseText.startsWith("HTTP/1.1 403"), responseText);
            }
        }
    }

    @Test void staticRoutingCannotReadProjectFilesOrEncodedTraversal() throws Exception {
        try (LocalUiServer server = server()) {
            for (String path : List.of("/ui/pom.xml", "/ui/%2e%2e/pom.xml", "/ui/../pom.xml",
                    "/ui/%2e%2e/%2e%2e/.git/config")) {
                HttpResponse<String> response = exchange(server, "GET", path, null, null, null);
                assertTrue(response.statusCode() == 404 || response.statusCode() == 400, path + ": " + response.body());
                assertFalse(response.body().contains("<project"), path);
                assertFalse(response.body().contains("[core]"), path);
            }
            assertEquals(405, exchange(server, "GET", "/api/preview", null, server.token(), null).statusCode());
            assertEquals(404, exchange(server, "GET", "/api/non-esiste", null, server.token(), null).statusCode());
        }
    }

    @Test void inspectAndRuleLoadingReturnRealFilesystemData() throws Exception {
        Path root = fixture("repository con spazi", true);
        try (LocalUiServer server = server()) {
            JsonNode inspection = postOk(server, "/api/inspect", Map.of("root", root.toString()));
            assertEquals(root.toRealPath().toString(), inspection.path("root").asText());
            assertEquals(root.getFileName().toString(), inspection.path("name").asText());
            assertEquals(json.valueToTree(List.of("config/application.yaml", "deploy/Blue_42/values.yaml")),
                    inspection.path("yamlFiles"));
            assertTrue(inspection.path("git").isNull());
            JsonNode rules = postOk(server, "/api/rules", Map.of("path", RULES.toString()));
            assertEquals(Files.readString(RULES), rules.path("yaml").asText());
            List<String> names = new ArrayList<>();
            rules.path("names").forEach(name -> names.add(name.asText()));
            assertEquals(List.of(RECIPE, "it.migration.demo.Valori", "it.migration.demo.NessunaModifica"), names);
        }
    }

    @Test void workspaceValidatesOnlyTheCanonicalDirectoryWithoutInspectingGitOrYaml() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("cartella migrazione"));
        Files.writeString(workspace.resolve("broken.yaml"), "items: [non chiuso\n");
        Files.writeString(workspace.resolve(".git"), "gitdir: directory-che-non-esiste\n");
        try (LocalUiServer server = server()) {
            JsonNode selected = postOk(server, "/api/workspace", Map.of("path", workspace.resolve(".").toString()));
            assertEquals(json.valueToTree(Map.of("path", workspace.toRealPath().toString())), selected);
            for (String invalid : List.of("", workspace.resolve("assente").toString(), workspace.resolve("broken.yaml").toString())) {
                HttpResponse<String> response = post(server, "/api/workspace", Map.of("path", invalid));
                assertEquals(400, response.statusCode(), response.body());
                assertFalse(json.readTree(response.body()).path("error").asText().isBlank());
            }
            assertEquals(401, exchange(server, "POST", "/api/workspace", Map.of("path", workspace.toString()), null, null).statusCode());
        }
        assertEquals("items: [non chiuso\n", Files.readString(workspace.resolve("broken.yaml")));
        assertFalse(Files.exists(workspace.resolve("assente")));
    }

    @Test void descriptorIncludesNestedRecipesAndCustomOptionsWithoutSerializationErrors() throws Exception {
        try (LocalUiServer server = server()) {
            JsonNode catalog = postOk(server, "/api/catalog", Map.of("yaml", Files.readString(RULES),
                    "recipe", RECIPE, "parameters", Map.of("migration.endpoint", "new-service")));
            String descriptor = json.writeValueAsString(catalog);
            assertTrue(descriptor.contains("it.migration.workbench.recipes.ChangeValueWhenPropertyEquals"), descriptor);
            assertTrue(descriptor.contains("conditionFile"), descriptor);
            assertTrue(descriptor.contains("conditionPath"), descriptor);
            assertTrue(descriptor.contains("newValue"), descriptor);
            assertTrue(descriptor.contains("org.openrewrite.yaml.ChangePropertyValue"), descriptor);
            assertFalse(descriptor.contains("InvalidDefinitionException"), descriptor);
        }
    }

    @Test void multiRepositoryPreviewUsesIndependentConditionsAndPreservesEveryOriginal() throws Exception {
        Path yes = fixture("servizio-ordini", true);
        Path no = fixture("servizio-pagamenti", false);
        write(yes, "deploy/Blue_42/values.yaml", resource("/golden/complex-before.yaml"));
        write(yes, "deploy/Preview-EU/values.yaml", SIMPLE);
        write(yes, "outside/values.yaml", SIMPLE);
        write(yes, "helm/templates/config.yaml", "metadata:\n  name: {{ .Release.Name }}\n");
        Map<String, String> beforeYes = WorkspaceFiles.inventory(yes);
        Map<String, String> beforeNo = WorkspaceFiles.inventory(no);
        try (LocalUiServer server = server()) {
            JsonNode job = finished(server, preview(server, List.of(yes, no), Files.readString(RULES), RECIPE,
                    Map.of("migration.endpoint", "new-service")));
            assertEquals("COMPLETED", job.path("status").asText(), job.toPrettyString());
            assertEquals(2, job.path("reports").size(), job.toPrettyString());
            JsonNode yesReport = null, noReport = null;
            for (JsonNode report : job.path("reports")) {
                if (report.path("root").asText().equals(yes.toRealPath().toString())) yesReport = report;
                if (report.path("root").asText().equals(no.toRealPath().toString())) noReport = report;
            }
            assertNotNull(yesReport, job.toPrettyString());
            assertNotNull(noReport, job.toPrettyString());
            assertEquals(resource("/golden/complex-after.yaml"), file(yesReport, "deploy/Blue_42/values.yaml").path("after").asText());
            assertEquals(EXPECTED, file(yesReport, "deploy/Preview-EU/values.yaml").path("after").asText());
            assertEquals(SIMPLE, file(yesReport, "outside/values.yaml").path("after").asText());
            assertEquals("SKIPPED", file(yesReport, "helm/templates/config.yaml").path("status").asText());
            assertTrue(file(yesReport, "deploy/Blue_42/values.yaml").path("diff").asText().contains("-  endpoint: \"old-service\""));
            assertEquals("app:\n  endpoint: new-service\n  timeout: 30s\ncredentials:\n  enabled: false\n",
                    file(noReport, "deploy/Blue_42/values.yaml").path("after").asText());
            assertEquals("UNCHANGED", file(noReport, "config/application.yaml").path("status").asText());
        }
        assertEquals(beforeYes, WorkspaceFiles.inventory(yes));
        assertEquals(beforeNo, WorkspaceFiles.inventory(no));
    }

    @Test void invalidTargetYamlProducesAnErrorReportAndNeverRewritesTheRoot() throws Exception {
        Path root = fixture("yaml-danneggiato", true);
        write(root, "broken.yaml", "items: [non chiuso\n");
        Map<String, String> before = WorkspaceFiles.inventory(root);
        try (LocalUiServer server = server()) {
            JsonNode job = finished(server, preview(server, List.of(root), Files.readString(RULES), RECIPE, Map.of()));
            assertEquals("COMPLETED", job.path("status").asText(), job.toPrettyString());
            assertEquals(1, job.path("reports").size(), job.toPrettyString());
            JsonNode report = job.path("reports").get(0);
            assertEquals("ERROR", file(report, "broken.yaml").path("status").asText());
            assertEquals(SIMPLE, file(report, "deploy/Blue_42/values.yaml").path("after").asText());
            boolean diagnosed = false;
            for (JsonNode diagnostic : report.path("diagnostics")) {
                if (diagnostic.path("severity").asText().equals("ERROR")) {
                    diagnosed = true;
                    assertFalse(diagnostic.path("message").asText().isBlank());
                }
            }
            assertTrue(diagnosed, report.toPrettyString());
        }
        assertEquals(before, WorkspaceFiles.inventory(root));
    }

    @Test void invalidRecipeAndMissingFilesProduceReadableClientErrors() throws Exception {
        try (LocalUiServer server = server()) {
            for (Map.Entry<String, Map<String, ?>> invalid : Map.<String, Map<String, ?>>of(
                    "/api/inspect", Map.of("root", temporary.resolve("assente").toString()),
                    "/api/rules", Map.of("path", temporary.resolve("assente.yml").toString()),
                    "/api/catalog", Map.of("yaml", Files.readString(RULES), "recipe", "it.NonEsiste", "parameters", Map.of())
            ).entrySet()) {
                HttpResponse<String> response = post(server, invalid.getKey(), invalid.getValue());
                assertEquals(400, response.statusCode(), response.body());
                String error = json.readTree(response.body()).path("error").asText();
                assertFalse(error.isBlank(), response.body());
                assertFalse(error.contains("\tat "), response.body());
            }
        }
    }

    @Test void malformedRequestsDoNotTurnIntoServerErrorsOrCreateJobs() throws Exception {
        try (LocalUiServer server = server()) {
            HttpRequest malformed = HttpRequest.newBuilder(server.baseUri().resolve("/api/preview"))
                    .header("X-Workbench-Token", server.token()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{malformed")).build();
            HttpResponse<String> response = client.send(malformed, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), response.body());
            assertFalse(json.readTree(response.body()).path("error").asText().isBlank());
            response = post(server, "/api/preview", Map.of("roots", List.of(), "yaml", Files.readString(RULES),
                    "recipe", RECIPE, "parameters", Map.of()));
            assertEquals(400, response.statusCode(), response.body());
        }
    }

    @Test void unsupportedMediaAndOversizedRequestsAreRejectedBeforeParsing() throws Exception {
        try (LocalUiServer server = server()) {
            HttpRequest wrongMedia = HttpRequest.newBuilder(server.baseUri().resolve("/api/inspect"))
                    .header("X-Workbench-Token", server.token()).header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            HttpResponse<String> response = client.send(wrongMedia, HttpResponse.BodyHandlers.ofString());
            assertEquals(415, response.statusCode(), response.body());
            HttpRequest tooLarge = HttpRequest.newBuilder(server.baseUri().resolve("/api/preview"))
                    .timeout(Duration.ofSeconds(20)).header("X-Workbench-Token", server.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(WorkspaceFiles.MAX_FILE_BYTES + 1))).build();
            response = client.send(tooLarge, HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode(), response.body());
            assertTrue(json.readTree(response.body()).path("error").asText().contains("8 MiB"));
        }
    }

    @Test void recipeFailuresAreReportedByTheJobWithoutPartialChanges() throws Exception {
        Path root = fixture("ricetta-non-valida", true);
        Map<String, String> before = WorkspaceFiles.inventory(root);
        String missingParameter = """
                type: specs.openrewrite.org/v1beta/recipe
                name: it.Test
                displayName: Test parametro richiesto
                description: Verifica un parametro mancante.
                recipeList:
                  - org.openrewrite.yaml.ChangePropertyValue:
                      propertyKey: app.endpoint
                      newValue: '${migration.mustBeProvided}'
                """;
        for (String yaml : List.of(Files.readString(RULES), missingParameter, "broken: [non chiuso\n")) {
            try (LocalUiServer server = server()) {
                String recipe = yaml.equals(missingParameter) ? "it.Test" : "it.NonEsiste";
                JsonNode job = finished(server, preview(server, List.of(root), yaml, recipe, Map.of()));
                assertEquals("FAILED", job.path("status").asText(), job.toPrettyString());
                assertEquals(0, job.path("reports").size(), job.toPrettyString());
                assertFalse(job.path("error").asText().isBlank(), job.toPrettyString());
                assertFalse(job.path("error").asText().contains("\tat "), job.toPrettyString());
                if (yaml.equals(missingParameter)) assertTrue(job.path("error").asText().contains("migration.mustBeProvided"));
            }
        }
        assertEquals(before, WorkspaceFiles.inventory(root));
    }

    @Test void aNewServerHasNoJobHistoryAndUsesANewToken() throws Exception {
        String oldId;
        String oldToken;
        Path root = fixture("sessione-senza-ripristino", true);
        try (LocalUiServer first = server()) {
            oldToken = first.token();
            oldId = preview(first, List.of(root), Files.readString(RULES), RECIPE, Map.of());
            assertEquals("COMPLETED", finished(first, oldId).path("status").asText());
        }
        try (LocalUiServer second = server()) {
            assertNotEquals(oldToken, second.token());
            HttpResponse<String> response = exchange(second, "GET", "/api/jobs/" + oldId, null, second.token(), null);
            assertEquals(404, response.statusCode(), response.body());
            assertEquals(401, exchange(second, "GET", "/api/bootstrap", null, oldToken, null).statusCode());
        }
    }
}
