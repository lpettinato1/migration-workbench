package it.migration.workbench;

import it.migration.workbench.model.Preview;
import org.openrewrite.*;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.tree.ParseError;
import org.openrewrite.yaml.YamlParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

import static it.migration.workbench.model.Preview.*;

/** Adattatore OpenRewrite: ogni invocazione possiede parser, ricette, contesto e accumulatori. */
public final class OpenRewriteEngine {
    public Report preview(Request request, Consumer<Progress> progress) throws IOException {
        long started = System.nanoTime();
        Path root = WorkspaceFiles.root(request.root());
        Path rules = Path.of(request.rules()).toRealPath();
        if (rules.startsWith(root)) throw new WorkbenchException("Tenere il file delle ricette fuori dalla root da migrare, per non elaborare la configurazione del runner.");
        RecipeCatalog.Loaded loaded = new RecipeCatalog().load(rules, request.recipe(), request.parameters());
        Map<String, FileResult> files = new TreeMap<>();
        Map<String, String> inventory = new TreeMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<SourceFile> parsed = new ArrayList<>();
        List<Throwable> parseErrors = new ArrayList<>();
        InMemoryExecutionContext context = new InMemoryExecutionContext(parseErrors::add);
        YamlParser parser = YamlParser.builder().build();
        long parseStarted = System.nanoTime();
        for (Path path : WorkspaceFiles.yamlFiles(root)) {
            String relative = WorkspaceFiles.relative(root, path);
            progress.accept(new Progress("READ", relative, "Lettura del file YAML"));
            String hash = WorkspaceFiles.hashFile(path);
            inventory.put(relative, hash);
            if (Files.isSymbolicLink(path) || Files.size(path) > WorkspaceFiles.MAX_FILE_BYTES) {
                String reason = Files.isSymbolicLink(path) ? "Link simbolico: il file non viene seguito." : "Il file supera il limite di 8 MiB.";
                files.put(relative, new FileResult(relative, relative, Status.SKIPPED, null, null, hash, hash, false, List.of(), ""));
                diagnostics.add(new Diagnostic(Severity.WARNING, "INPUT_SKIPPED", relative, reason));
                continue;
            }
            WorkspaceFiles.safePath(root, relative);
            byte[] bytes = Files.readAllBytes(path);
            if (!WorkspaceFiles.sha256(bytes).equals(hash)) throw new WorkbenchException("Il file è cambiato durante la lettura: " + relative);
            String before;
            try { before = WorkspaceFiles.decode(bytes); }
            catch (CharacterCodingException e) {
                files.put(relative, new FileResult(relative, relative, Status.SKIPPED, null, null, hash, hash, false, List.of(), ""));
                diagnostics.add(new Diagnostic(Severity.WARNING, "UNSUPPORTED_ENCODING", relative, "Questo incremento supporta UTF-8 con o senza BOM. File lasciato intatto."));
                continue;
            }
            boolean bom = WorkspaceFiles.hasBom(bytes);
            FileResult original = new FileResult(relative, relative, Status.UNCHANGED, before, before, hash, hash, bom, List.of(), "");
            files.put(relative, original);
            if (looksTemplated(before)) {
                files.put(relative, new FileResult(relative, relative, Status.SKIPPED, before, before, hash, hash, bom, List.of(), ""));
                diagnostics.add(new Diagnostic(Severity.WARNING, "HELM_TEMPLATE", relative, "Rilevato un delimitatore Helm non quotato. Il file non viene interpretato o riscritto."));
                continue;
            }
            parseErrors.clear();
            try {
                validateYaml(before);
                SourceFile source = parser.parseInputs(List.of(new Parser.Input(path, () -> new ByteArrayInputStream(bytes))), root, context).findFirst().orElseThrow();
                if (source instanceof ParseError error) throw new WorkbenchException(error.getMarkers().findFirst(ParseExceptionResult.class)
                        .map(e -> e.getMessage().lines().findFirst().orElse(e.getExceptionType()))
                        .orElse("Il parser non ha riconosciuto il documento."));
                if (!parseErrors.isEmpty()) throw new WorkbenchException(readable(parseErrors.getFirst()));
                if (!Arrays.equals(source.printAllAsBytes(), bytes)) throw new WorkbenchException("Il parser non conserva esattamente il contenuto originale.");
                parsed.add(source);
            } catch (RuntimeException e) {
                files.put(relative, new FileResult(relative, relative, Status.ERROR, before, before, hash, hash, bom, List.of(), ""));
                diagnostics.add(new Diagnostic(Severity.ERROR, "YAML_PARSE_ERROR", relative, "File YAML non elaborabile: " + readable(e)));
            }
        }
        long parseMillis = elapsed(parseStarted);
        long executionMillis = 0;
        if (diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
            progress.accept(new Progress("REWRITE", null, "Esecuzione della ricetta " + request.recipe()));
            List<Throwable> executionErrors = new ArrayList<>();
            InMemoryExecutionContext executionContext = new InMemoryExecutionContext(executionErrors::add);
            long executionStarted = System.nanoTime();
            try {
                List<Result> results = loaded.recipe().run(new InMemoryLargeSourceSet(parsed), executionContext).getChangeset().getAllResults();
                if (!executionErrors.isEmpty()) throw new WorkbenchException(readable(executionErrors.getFirst()));
                Map<String, FileResult> rewritten = new TreeMap<>(files);
                for (Result result : results) {
                    SourceFile oldFile = result.getBefore(), newFile = result.getAfter();
                    String oldPath = oldFile == null ? null : oldFile.getSourcePath().toString().replace('\\', '/');
                    String newPath = newFile == null ? null : newFile.getSourcePath().toString().replace('\\', '/');
                    if (oldPath != null) WorkspaceFiles.safePath(root, oldPath);
                    if (newPath != null) WorkspaceFiles.safePath(root, newPath);
                    if (newPath != null && !newPath.equals(oldPath) && Files.exists(root.resolve(newPath), LinkOption.NOFOLLOW_LINKS))
                        throw new WorkbenchException("La ricetta crea o rinomina un file verso una destinazione già esistente: " + newPath);
                    FileResult original = oldPath == null ? null : files.get(oldPath);
                    if (oldFile != null && (original == null || original.status() != Status.UNCHANGED)) throw new WorkbenchException("La ricetta ha modificato un file non disponibile fra gli input: " + oldPath);
                    boolean bom = newFile != null && newFile.isCharsetBomMarked();
                    String after = newFile == null ? null : WorkspaceFiles.decode(newFile.printAllAsBytes());
                    byte[] newBytes = after == null ? null : WorkspaceFiles.encode(after, bom);
                    if (newFile != null) {
                        validateYaml(after);
                        List<Throwable> outputErrors = new ArrayList<>();
                        SourceFile verified = YamlParser.builder().build().parseInputs(
                                List.of(new Parser.Input(root.resolve(newPath), () -> new ByteArrayInputStream(newBytes))), root,
                                new InMemoryExecutionContext(outputErrors::add)).findFirst().orElseThrow();
                        if (verified instanceof ParseError || !outputErrors.isEmpty() || !Arrays.equals(verified.printAllAsBytes(), newBytes))
                            throw new WorkbenchException("La ricetta produce YAML non valido o non conservabile: " + newPath);
                    }
                    Status status = oldFile == null ? Status.CREATED : newFile == null ? Status.DELETED : !oldPath.equals(newPath) ? Status.RENAMED : Status.MODIFIED;
                    if (status == Status.MODIFIED && original.beforeSha256().equals(WorkspaceFiles.sha256(newBytes))) continue;
                    List<String> recipes = result.getRecipes().stream().flatMap(Collection::stream).map(Recipe::getName).distinct().toList();
                    FileResult change = new FileResult(oldPath, newPath, status, original == null ? null : original.before(), after,
                            original == null ? null : original.beforeSha256(), after == null ? null : WorkspaceFiles.sha256(newBytes),
                            newFile == null && original != null ? original.bom() : bom, recipes, result.diff());
                    rewritten.put(oldPath == null ? newPath : oldPath, change);
                }
                files = rewritten;
            } catch (Exception e) {
                diagnostics.add(new Diagnostic(Severity.ERROR, "RECIPE_EXECUTION_ERROR", null, "Ricetta interrotta; nessun risultato parziale applicabile: " + readable(e)));
            }
            executionMillis = elapsed(executionStarted);
        }
        String inputHash = WorkspaceFiles.inventoryHash(inventory);
        if (!inputHash.equals(WorkspaceFiles.inventoryHash(WorkspaceFiles.inventory(root))))
            diagnostics.add(new Diagnostic(Severity.ERROR, "INPUT_CHANGED", null, "Il contenuto della root è cambiato durante il dry run. Generare una nuova anteprima."));
        progress.accept(new Progress("COMPLETE", null, "Anteprima completata; file originali non modificati"));
        return new Report(Preview.SCHEMA_VERSION, Preview.TOOL_VERSION, Preview.ENGINE_VERSION, Instant.now().toString(),
                root.toString(), request.recipe(), loaded.yaml(), loaded.sha256(), inputHash, null,
                List.copyOf(files.values()), diagnostics, new Metrics(inventory.size(), parseMillis, executionMillis, elapsed(started)));
    }

    static boolean looksTemplated(String text) {
        boolean single = false, quoted = false, comment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { comment = false; continue; }
            if (comment) continue;
            if (quoted && c == '\\') { i++; continue; }
            if (!single && c == '"') { quoted = !quoted; continue; }
            if (!quoted && c == '\'') {
                if (single && i + 1 < text.length() && text.charAt(i + 1) == '\'') { i++; continue; }
                single = !single; continue;
            }
            if (!single && !quoted) {
                if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) comment = true;
                else if (c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '{') return true;
            }
        }
        return false;
    }

    static String readable(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String text = cause.getMessage();
        return cause.getClass().getSimpleName() + (text == null ? "" : ": " + text.replace('\r', ' ').replace('\n', ' '));
    }

    private static void validateYaml(String text) {
        var options = new org.yaml.snakeyaml.LoaderOptions();
        options.setCodePointLimit(WorkspaceFiles.MAX_FILE_BYTES);
        options.setMaxAliasesForCollections(5000);
        options.setNestingDepthLimit(300);
        var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options));
        // Compose verifica sintassi e riferimenti senza convertire o riscrivere tipi, tag o grafi YAML.
        for (var ignored : yaml.composeAll(new java.io.StringReader(text))) { }
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
