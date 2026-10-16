package it.migration.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.migration.workbench.model.Preview.Report;
import it.migration.workbench.model.Preview.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Il pacchetto distribuito deve servire anche frontend e preview, senza tool di sviluppo. */
@Timeout(90)
class UiStandaloneIT extends TestWorkspace {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private static final Pattern LAUNCH_LINK = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/#token=[A-Za-z0-9_-]+");
    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    @Test void packagedUiServesOfflineAngularAndRunsARealPreviewWithoutWritingInputs() throws Exception {
        Path runner = Files.createDirectory(temporary.resolve("interfaccia standalone senza build"));
        Files.copy(Path.of(System.getProperty("workbench.jar")), runner.resolve("workbench.jar"));
        Path rules = write(runner, "examples/migration.yml", Files.readString(RULES));
        Path root = runner.resolve("examples/repository");
        write(root, "config/application.yaml", "security:\n  enabled: true\n");
        write(root, "deploy/Blue_42/values.yaml", SIMPLE);
        write(root, "deploy/Arbitrary-EU-2026/values.yaml", "# Ambiente arbitrario\n" + SIMPLE);
        write(root, "helm/templates/configmap.yaml", "metadata:\n  name: {{ .Release.Name }}\n");
        Map<String, String> before = WorkspaceFiles.inventory(root);
        assertFalse(Files.exists(runner.resolve("pom.xml")));
        assertFalse(Files.exists(runner.resolve("node_modules")));
        assertFalse(Files.exists(runner.resolve(".mvn")));

        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java,
                "-Djava.awt.headless=true", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
                "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
                "-jar", runner.resolve("workbench.jar").toString(), "ui", "--no-browser", "--directory", runner.toString())
                .directory(runner.toFile()).redirectErrorStream(true);
        builder.environment().remove("CLASSPATH");
        builder.environment().remove("MAVEN_HOME");
        builder.environment().remove("M2_HOME");
        Process process = builder.start();
        CompletableFuture<URI> launched = new CompletableFuture<>();
        StringBuffer output = new StringBuffer();
        Thread reader = Thread.ofPlatform().daemon().name("packaged-ui-output").start(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    output.append(line).append('\n');
                    Matcher link = LAUNCH_LINK.matcher(line);
                    if (link.find()) launched.complete(URI.create(link.group()));
                }
                if (!launched.isDone()) launched.completeExceptionally(new IllegalStateException("Avvio UI interrotto: " + output));
            } catch (Exception failure) { launched.completeExceptionally(failure); }
        });

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            URI launch;
            try { launch = launched.get(20, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError("Il JAR non mostra il collegamento UI: " + output, failure); }
            URI base = URI.create(launch.getScheme() + "://" + launch.getAuthority());
            String token = launch.getFragment().substring("token=".length());
            assertTrue(process.isAlive(), output.toString());

            HttpResponse<String> index = get(client, base.resolve("/"), null);
            assertEquals(200, index.statusCode(), index.body());
            assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(index.body().contains("<mw-app>"), index.body());
            Matcher scripts = SCRIPT.matcher(index.body());
            int scriptCount = 0;
            while (scripts.find()) {
                URI script = base.resolve(scripts.group(1));
                assertEquals(base.getAuthority(), script.getAuthority(), "La UI deve usare asset inclusi nel JAR.");
                HttpResponse<String> asset = get(client, script, null);
                assertEquals(200, asset.statusCode(), script.toString());
                assertTrue(asset.headers().firstValue("Content-Type").orElse("").contains("javascript"), script.toString());
                assertTrue(asset.body().length() > 100, script.toString());
                assertFalse(asset.body().stripLeading().startsWith("<!doctype html>"), script.toString());
                scriptCount++;
            }
            assertTrue(scriptCount > 0, "L'HTML non contiene il bundle JavaScript Angular: " + index.body());
            assertFalse(index.body().contains("https://"), "L'avvio non deve dipendere da CDN esterne.");

            JsonNode bootstrap = readOk(get(client, base.resolve("/api/bootstrap"), token));
            assertEquals(runner.toRealPath().toString(), bootstrap.path("baseDirectory").asText());
            assertFalse(bootstrap.has("exampleRoot"));
            assertFalse(bootstrap.has("exampleRules"));
            assertFalse(bootstrap.has("exampleRecipe"));
            JsonNode loaded = readOk(post(client, base.resolve("/api/rules"), token, Map.of("path", rules.toString())));
            assertEquals(Files.readString(RULES), loaded.path("yaml").asText());
            HttpResponse<String> submitted = post(client, base.resolve("/api/preview"), token, Map.of(
                    "roots", List.of(root.toString()), "yaml", loaded.path("yaml").asText(),
                    "recipe", RECIPE, "parameters", Map.of("migration.endpoint", "new-service")));
            assertEquals(202, submitted.statusCode(), submitted.body());
            String id = json.readTree(submitted.body()).path("id").asText();
            assertFalse(id.isBlank());
            JsonNode job = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                job = readOk(get(client, base.resolve("/api/jobs/" + id), token));
                if (List.of("COMPLETED", "FAILED").contains(job.path("status").asText())) break;
                Thread.sleep(40);
            }
            assertNotNull(job);
            assertEquals("COMPLETED", job.path("status").asText(), job.toPrettyString() + "\n" + output);
            assertEquals(1, job.path("reports").size(), job.toPrettyString());
            Report report = json.treeToValue(job.path("reports").get(0), Report.class);
            assertFalse(report.hasErrors(), report.diagnostics().toString());
            assertEquals(4, report.metrics().discoveredFiles());
            assertEquals(2, report.changedFiles());
            assertEquals(1, report.files().stream().filter(file -> file.status() == Status.SKIPPED).count());
            assertEquals(EXPECTED, file(report, "deploy/Blue_42/values.yaml").after());
            assertEquals("# Ambiente arbitrario\n" + EXPECTED, file(report, "deploy/Arbitrary-EU-2026/values.yaml").after());
            assertTrue(file(report, "deploy/Blue_42/values.yaml").diff().contains("-  endpoint: old-service"));
            assertEquals(before, WorkspaceFiles.inventory(root), "La UI standalone deve preservare tutti gli originali.");
            assertTrue(process.isAlive(), output.toString());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Il processo UI non termina.");
            reader.join(2_000);
        }
    }

    private HttpResponse<String> get(HttpClient client, URI uri, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("X-Workbench-Token", token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, URI uri, String token, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("X-Workbench-Token", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode readOk(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
