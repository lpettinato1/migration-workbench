package it.migration.workbench.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.migration.workbench.GitClient;
import it.migration.workbench.MigrationService;
import it.migration.workbench.RecipeCatalog;
import it.migration.workbench.WorkbenchException;
import it.migration.workbench.WorkspaceFiles;
import it.migration.workbench.model.Preview;
import it.migration.workbench.ui.UiGitWorkflow.OperationState;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.GraphicsEnvironment;
import java.awt.Component;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Adapter HTTP locale: stato della sessione solo in memoria, stesso motore della CLI. */
public final class LocalUiServer implements AutoCloseable {
    private static final int BODY_LIMIT = WorkspaceFiles.MAX_FILE_BYTES;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon",
            "woff", "font/woff", "woff2", "font/woff2");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpServer server;
    private final Path baseDirectory;
    private final String token;
    private final URI baseUri;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "migration-preview");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicBoolean pickerOpen = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread shutdownHook = new Thread(this::close, "migration-ui-shutdown");
    private Job activeJob;
    private boolean mutating;

    private LocalUiServer(Path baseDirectory, int port) throws IOException {
        if (port < 0 || port > 65535) throw new WorkbenchException("La porta deve essere compresa tra 0 e 65535.");
        this.baseDirectory = WorkspaceFiles.root(baseDirectory.toString());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/", this::handle);
        server.setExecutor(requests);
    }

    public static LocalUiServer start(Path baseDirectory, int port) throws IOException {
        LocalUiServer result = new LocalUiServer(baseDirectory, port);
        Runtime.getRuntime().addShutdownHook(result.shutdownHook);
        result.server.start();
        return result;
    }

    public URI baseUri() { return baseUri; }
    public String token() { return token; }
    public URI launchUri() { return URI.create(baseUri + "/#token=" + token); }
    public void await() throws InterruptedException { stopped.await(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            if (!baseUri.getAuthority().equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Host"))) {
                respond(exchange, 403, Map.of("error", "Host non autorizzato."));
                return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if ((origin != null && !origin.equals(baseUri.toString())) ||
                    "cross-site".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) {
                respond(exchange, 403, Map.of("error", "Origine della richiesta non autorizzata."));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (path.startsWith("/api/")) {
                String supplied = exchange.getRequestHeaders().getFirst("X-Workbench-Token");
                if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.UTF_8))) {
                    respond(exchange, 401, Map.of("error", "Sessione non autorizzata. Aprire il collegamento mostrato dall'applicazione."));
                    return;
                }
                api(exchange, path);
            } else if (exchange.getRequestMethod().equals("GET") && (path.equals("/") || path.equals("/ui") || path.equals("/ui/"))) {
                asset(exchange, "/ui/index.html");
            } else if (exchange.getRequestMethod().equals("GET") && path.matches("/ui/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.(?:html|js|css|json|svg|png|ico|woff2?)") && !path.contains("..")) {
                asset(exchange, path);
            } else respond(exchange, 404, Map.of("error", "Risorsa non trovata."));
        } catch (UiGitWorkflow.Rejected failure) {
            respondSafely(exchange, failure.reason() == UiGitWorkflow.Reason.NOT_FOUND ? 404 : 409, failure.getMessage());
        } catch (HttpFailure failure) {
            respondSafely(exchange, failure.status, failure.getMessage());
        } catch (WorkbenchException | IllegalArgumentException | IOException failure) {
            respondSafely(exchange, 400, readable(failure));
        } catch (Exception failure) {
            respondSafely(exchange, 500, "Operazione non riuscita: " + readable(failure));
        } finally { exchange.close(); }
    }

    private void api(HttpExchange exchange, String path) throws Exception {
        String method = exchange.getRequestMethod();
        if (!List.of("/api/bootstrap", "/api/workspace", "/api/inspect", "/api/rules", "/api/catalog", "/api/preview", "/api/pick", "/api/git/details", "/api/git/prepare").contains(path) && !path.startsWith("/api/jobs/"))
            throw new HttpFailure(404, "Operazione non disponibile.");
        if (path.startsWith("/api/jobs/")) { jobApi(exchange, path); return; }
        if (method.equals("GET") && path.equals("/api/bootstrap")) {
            respond(exchange, 200, Map.of("toolVersion", Preview.TOOL_VERSION, "engineVersion", Preview.ENGINE_VERSION,
                    "baseDirectory", baseDirectory.toString()));
        } else if (method.equals("POST")) {
            JsonNode body = body(exchange);
            switch (path) {
                case "/api/workspace" -> respond(exchange, 200, Map.of("path", WorkspaceFiles.root(text(body, "path")).toString()));
                case "/api/inspect" -> {
                    Path root = WorkspaceFiles.root(text(body, "root"));
                    respond(exchange, 200, inspect(root));
                }
                case "/api/git/details" -> respond(exchange, 200, new GitClient().details(Path.of(text(body, "root"))));
                case "/api/git/prepare" -> {
                    confirmed(body);
                    Path repository = Path.of(text(body, "repository"));
                    String source = text(body, "source"), branch = text(body, "branch");
                    Path worktree = Path.of(text(body, "worktree"));
                    synchronized (jobs) { requireIdle(); mutating = true; }
                    try { respond(exchange, 200, inspect(new GitClient().prepare(repository, source, branch, worktree))); }
                    finally { synchronized (jobs) { mutating = false; } }
                }
                case "/api/rules" -> {
                    Path rules = Path.of(text(body, "path")).toRealPath();
                    if (!Files.isRegularFile(rules) || Files.size(rules) > BODY_LIMIT)
                        throw new WorkbenchException("Selezionare un file di ricette UTF-8 fino a 8 MiB.");
                    byte[] bytes;
                    try (var input = Files.newInputStream(rules)) { bytes = input.readNBytes(BODY_LIMIT + 1); }
                    if (bytes.length > BODY_LIMIT) throw new HttpFailure(413, "File delle ricette troppo grande: massimo 8 MiB.");
                    String yaml = WorkspaceFiles.decode(bytes);
                    respond(exchange, 200, Map.of("yaml", yaml, "names", recipeNames(yaml)));
                }
                case "/api/catalog" -> {
                    String yaml = text(body, "yaml");
                    String recipe = text(body, "recipe");
                    Path temporary = temporaryRules(yaml, List.of());
                    try { respond(exchange, 200, new RecipeCatalog().load(temporary, recipe, parameters(body)).recipe().getDescriptor()); }
                    finally { Files.deleteIfExists(temporary); }
                }
                case "/api/preview" -> respond(exchange, 202, Map.of("id", submit(body)));
                case "/api/pick" -> {
                    JsonNode directory = body.get("directory");
                    if (directory != null && !directory.isNull() && !directory.isTextual())
                        throw new WorkbenchException("La cartella iniziale deve essere un percorso testuale.");
                    Path initial = directory == null || directory.isNull() || directory.textValue().isBlank()
                            ? baseDirectory : WorkspaceFiles.root(directory.textValue());
                    respond(exchange, 200, pick(text(body, "kind"), initial));
                }
                default -> throw new HttpFailure(404, "Operazione non disponibile.");
            }
        } else throw new HttpFailure(405, "Metodo HTTP non consentito.");
    }

    private Map<String, Object> inspect(Path root) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", root.toString());
        result.put("name", root.getFileName() == null ? root.toString() : root.getFileName().toString());
        result.put("git", new GitClient().snapshot(root));
        result.put("yamlFiles", WorkspaceFiles.yamlFiles(root).stream().map(file -> WorkspaceFiles.relative(root, file)).toList());
        return result;
    }

    private void jobApi(HttpExchange exchange, String path) throws IOException {
        String[] parts = path.substring("/api/jobs/".length()).split("/", -1);
        if (parts.length > 2 || parts[0].isBlank()) throw new HttpFailure(404, "Operazione non disponibile.");
        Job job;
        synchronized (jobs) { job = jobs.get(parts[0]); }
        if (job == null) throw new HttpFailure(404, "Anteprima non trovata in questa sessione.");
        String action = parts.length == 1 ? "" : parts[1];
        String method = exchange.getRequestMethod();
        if (action.isEmpty() && parts.length == 1 && method.equals("GET")) {
            respond(exchange, 200, job.snapshot());
        } else if (action.equals("operations") && method.equals("GET")) {
            List<OperationState> operations;
            boolean busy;
            synchronized (jobs) {
                operations = job.workflow == null ? List.of() : job.workflow.snapshot();
                busy = mutating || activeJob != null;
            }
            respond(exchange, 200, Map.of("operations", operations, "busy", busy));
        } else if (List.of("review", "apply", "commit", "push").contains(action)) {
            if (!method.equals("POST")) throw new HttpFailure(405, "Metodo HTTP non consentito.");
            respond(exchange, 200, operate(job, action, body(exchange)));
        } else if (action.isEmpty() || action.equals("operations")) {
            throw new HttpFailure(405, "Metodo HTTP non consentito.");
        } else throw new HttpFailure(404, "Operazione non disponibile.");
    }

    private OperationState operate(Job job, String action, JsonNode body) throws IOException {
        confirmed(body);
        Path root = WorkspaceFiles.root(text(body, "root"));
        String message = action.equals("commit") ? text(body, "message") : null;
        String remote = action.equals("push") ? text(body, "remote") : null;
        List<String> reviewedPaths = null;
        if (action.equals("review")) {
            JsonNode reviewed = body.get("reviewedPaths");
            if (reviewed == null || !reviewed.isArray()) throw new WorkbenchException("Indicare i percorsi dei file verificati.");
            reviewedPaths = new ArrayList<>();
            for (JsonNode value : reviewed) {
                if (!value.isTextual()) throw new WorkbenchException("I percorsi verificati devono essere testuali.");
                reviewedPaths.add(value.textValue());
            }
        }
        UiGitWorkflow workflow;
        synchronized (jobs) {
            requireIdle();
            if (jobs.get(job.id) != job) throw new HttpFailure(404, "L'anteprima non è più disponibile in questa sessione.");
            if (!job.status.equals("COMPLETED") || job.workflow == null) throw new HttpFailure(409, "Attendere un'anteprima completata prima di procedere.");
            workflow = job.workflow;
            mutating = true;
        }
        try { return workflow.perform(UiGitWorkflow.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT)), root, reviewedPaths, message, remote); }
        finally { synchronized (jobs) { mutating = false; } }
    }

    private static void confirmed(JsonNode body) {
        JsonNode value = body.get("confirmed");
        if (value == null || !value.isBoolean() || !value.booleanValue()) throw new WorkbenchException("L'operazione richiede una conferma esplicita.");
    }

    private void requireIdle() {
        if (activeJob != null || mutating) throw new HttpFailure(409, "È già in corso un'anteprima o un'operazione. Attendere il completamento.");
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
            throw new HttpFailure(415, "Usare Content-Type application/json.");
        byte[] bytes = exchange.getRequestBody().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) throw new HttpFailure(413, "Richiesta troppo grande: massimo 8 MiB.");
        try {
            JsonNode result = json.readTree(bytes);
            if (result == null || !result.isObject()) throw new HttpFailure(400, "La richiesta deve contenere un oggetto JSON.");
            return result;
        } catch (JsonProcessingException failure) { throw new HttpFailure(400, "JSON non valido: " + failure.getOriginalMessage()); }
    }

    private String submit(JsonNode body) throws IOException {
        String yaml = text(body, "yaml");
        String recipe = text(body, "recipe");
        Map<String, String> parameters = parameters(body);
        JsonNode values = body.get("roots");
        if (values == null || !values.isArray() || values.isEmpty()) throw new WorkbenchException("Selezionare almeno una repository/cartella.");
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new WorkbenchException("Le root devono essere percorsi testuali.");
            roots.add(WorkspaceFiles.root(value.textValue()));
        }
        synchronized (jobs) {
            requireIdle();
            boolean uncommitted = jobs.values().stream().anyMatch(job -> job.workflow != null && job.workflow.hasAppliedGit());
            if (uncommitted) throw new HttpFailure(409, "Una migrazione Git è già stata applicata. Completare il commit prima di generare una nuova anteprima.");
            while (jobs.size() >= 3) jobs.remove(jobs.keySet().iterator().next());
            for (Job previous : jobs.values()) if (previous.workflow != null) previous.workflow.invalidateReview();
            Job job = new Job(java.util.UUID.randomUUID().toString());
            activeJob = job;
            jobs.put(job.id, job);
            worker.submit(() -> execute(job, List.copyOf(roots), yaml, recipe, parameters));
            return job.id;
        }
    }

    private void execute(Job job, List<Path> roots, String yaml, String recipe, Map<String, String> parameters) {
        Path temporary = null;
        try {
            job.status = "RUNNING";
            temporary = temporaryRules(yaml, roots);
            MigrationService service = new MigrationService();
            for (int index = 0; index < roots.size(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Anteprima interrotta.");
                Path root = roots.get(index);
                int current = index + 1;
                job.progress = new JobProgress(root.toString(), current, roots.size(), "START", null, "Analisi di " + root.getFileName());
                Preview.Report report = service.preview(new Preview.Request(root.toString(), temporary.toString(), recipe, parameters),
                        event -> job.progress = new JobProgress(root.toString(), current, roots.size(), event.phase(), event.path(), event.message()));
                List<Preview.Report> completed = new ArrayList<>(job.reports);
                completed.add(report);
                job.reports = List.copyOf(completed);
            }
        } catch (Exception failure) {
            job.error = readable(failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) {
                    job.error = "Impossibile eliminare il temporaneo delle ricette: " + readable(failure);
                }
            }
            synchronized (jobs) {
                activeJob = null;
                if (job.error == null) job.workflow = new UiGitWorkflow(job.reports);
                job.status = job.error == null ? "COMPLETED" : "FAILED";
            }
        }
    }

    private Path temporaryRules(String yaml, List<Path> roots) throws IOException {
        if (yaml.getBytes(StandardCharsets.UTF_8).length > BODY_LIMIT) throw new HttpFailure(413, "Ricette troppo grandi: massimo 8 MiB.");
        Path directory = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (roots.stream().anyMatch(directory::startsWith))
            throw new WorkbenchException("La cartella temporanea è contenuta in una root selezionata. Selezionare la cartella del repository, non una sua directory superiore.");
        Path file = Files.createTempFile(directory, "migration-ui-rules-", ".yml");
        try { Files.writeString(file, yaml, StandardCharsets.UTF_8); }
        catch (IOException failure) { Files.deleteIfExists(file); throw failure; }
        return file;
    }

    private static List<String> recipeNames(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(BODY_LIMIT);
        options.setNestingDepthLimit(200);
        options.setMaxAliasesForCollections(500);
        List<String> names = new ArrayList<>();
        try {
            for (Object document : new Yaml(new SafeConstructor(options)).loadAll(yaml)) {
                if (document instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.isBlank()) names.add(name);
            }
        } catch (RuntimeException failure) { throw new WorkbenchException("File delle ricette non valido: " + readable(failure), failure); }
        return List.copyOf(names);
    }

    private Map<String, Object> pick(String kind, Path directory) throws Exception {
        if (!kind.equals("directory") && !kind.equals("rules")) throw new WorkbenchException("Tipo di selezione non valido.");
        if (GraphicsEnvironment.isHeadless()) throw new WorkbenchException("La selezione grafica non è disponibile. Inserire il percorso nella casella.");
        if (!pickerOpen.compareAndSet(false, true)) throw new HttpFailure(409, "È già aperta una finestra di selezione.");
        try {
            AtomicReference<String> chosen = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) { /* Il selettore resta disponibile con il tema Swing. */ }
                JFileChooser chooser = new JFileChooser(directory.toFile()) {
                    @Override protected JDialog createDialog(Component parent) {
                        JDialog dialog = super.createDialog(parent);
                        dialog.setAlwaysOnTop(true);
                        return dialog;
                    }
                };
                chooser.setDialogTitle(kind.equals("directory") ? "Seleziona una cartella" : "Apri ricette YAML");
                chooser.setFileSelectionMode(kind.equals("directory") ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
                if (kind.equals("rules")) chooser.setFileFilter(new FileNameExtensionFilter("Ricette YAML", "yml", "yaml"));
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chosen.set(chooser.getSelectedFile().getAbsolutePath());
            });
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", chosen.get());
            return result;
        } finally { pickerOpen.set(false); }
    }

    private static String text(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new WorkbenchException("Specificare '" + key + "'.");
        return value.textValue();
    }

    private static Map<String, String> parameters(JsonNode body) {
        JsonNode source = body.get("parameters");
        if (source == null || source.isNull()) return Map.of();
        if (!source.isObject()) throw new WorkbenchException("I parametri devono essere una mappa nome/valore.");
        Map<String, String> result = new LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw new WorkbenchException("Valore non testuale per il parametro " + entry.getKey());
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    private void asset(HttpExchange exchange, String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) { respond(exchange, 404, Map.of("error", "Interfaccia non presente nel pacchetto.")); return; }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPES.get(name.substring(name.lastIndexOf('.') + 1)));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void respondSafely(HttpExchange exchange, int status, String message) {
        try { respond(exchange, status, Map.of("error", message)); }
        catch (IOException ignored) { /* Il client può aver chiuso la richiesta. */ }
    }

    private static String readable(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null || failure.getMessage().isBlank() ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        worker.shutdownNow();
        requests.shutdownNow();
        stopped.countDown();
        if (Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* La JVM sta già terminando. */ }
        }
    }

    public record JobProgress(String root, int index, int total, String phase, String path, String message) { }
    private static final class Job {
        private final String id;
        private volatile String status = "QUEUED";
        private volatile JobProgress progress;
        private volatile List<Preview.Report> reports = List.of();
        private volatile String error;
        private UiGitWorkflow workflow;
        private Job(String id) { this.id = id; }
        private Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", status);
            result.put("progress", progress);
            result.put("reports", reports);
            if (error != null) result.put("error", error);
            return result;
        }
    }

    private static final class HttpFailure extends RuntimeException {
        private final int status;
        private HttpFailure(int status, String message) { super(message); this.status = status; }
    }
}
