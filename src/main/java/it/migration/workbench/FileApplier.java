package it.migration.workbench;

import it.migration.workbench.model.Preview.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Preflight su tutto il contesto e sostituzioni con recupero best effort in caso di errore I/O. */
public final class FileApplier {
    @FunctionalInterface interface Writer { void write(Path path, byte[] content) throws IOException; }
    private final Writer writer;
    public FileApplier() { this(WorkspaceFiles::atomicWrite); }
    FileApplier(Writer writer) { this.writer = writer; }

    public int apply(Report report) throws IOException {
        validateReport(report);
        Path root = WorkspaceFiles.root(report.root());
        if (!WorkspaceFiles.inventoryHash(WorkspaceFiles.inventory(root)).equals(report.inputSha256()))
            throw new WorkbenchException("Un file YAML è stato modificato, aggiunto o rimosso dall'anteprima, anche se non era fra quelli da riscrivere. Nessuna scrittura eseguita.");
        Map<Path, byte[]> desired = desiredFiles(report, root);
        LinkedHashMap<Path, byte[]> originals = new LinkedHashMap<>();
        for (var entry : desired.entrySet()) {
            Path path = entry.getKey();
            originals.put(path, Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(path) : null);
        }
        List<Path> attempted = new ArrayList<>();
        try {
            for (var entry : desired.entrySet()) {
                Path path = entry.getKey();
                // Ricontrollo subito prima della sostituzione: nessuna sovrascrittura di un nuovo contenuto.
                byte[] now = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(path) : null;
                if (!Arrays.equals(now, originals.get(path))) throw new WorkbenchException("Il file è cambiato durante l'applicazione: " + path);
                WorkspaceFiles.safePath(root, WorkspaceFiles.relative(root, path));
                attempted.add(path);
                if (entry.getValue() == null) Files.delete(path); else writer.write(path, entry.getValue());
            }
            verifyApplied(report);
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(attempted);
            List<String> recoveryErrors = new ArrayList<>();
            for (Path path : attempted) {
                try {
                    byte[] original = originals.get(path);
                    byte[] current = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(path) : null;
                    if (Arrays.equals(current, original)) continue;
                    if (original == null) Files.deleteIfExists(path); else WorkspaceFiles.atomicWrite(path, original);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    recoveryErrors.add(WorkspaceFiles.relative(root, path) + ": " + rollbackFailure.getMessage());
                }
            }
            throw new WorkbenchException("Applicazione interrotta. Tentato il ripristino dei contenuti già toccati; " +
                    (recoveryErrors.isEmpty() ? "ripristino completato." : "ripristino incompleto: " + String.join("; ", recoveryErrors) + ". Verificare i file e Git.") +
                    " Causa: " + failure.getMessage(), failure);
        }
        return (int) report.changedFiles();
    }

    public void verifyApplied(Report report) throws IOException {
        validateReport(report);
        Path root = WorkspaceFiles.root(report.root());
        Map<String, String> expected = beforeInventory(report);
        Map<Path, byte[]> desired = desiredFiles(report, root, false);
        for (var file : report.files()) {
            if (!file.changesContent()) continue;
            if (file.beforePath() != null) expected.remove(file.beforePath());
            if (file.afterPath() != null && isYaml(file.afterPath())) expected.put(file.afterPath(), file.afterSha256());
        }
        if (!expected.equals(WorkspaceFiles.inventory(root))) throw new WorkbenchException("Il contenuto YAML attuale non corrisponde al risultato approvato.");
        for (var entry : desired.entrySet()) {
            Path path = entry.getKey();
            if (entry.getValue() == null) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new WorkbenchException("Un file da eliminare è ancora presente: " + path);
            } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !WorkspaceFiles.hashFile(path).equals(WorkspaceFiles.sha256(entry.getValue())))
                throw new WorkbenchException("Il risultato del file è diverso dall'anteprima: " + path);
        }
    }

    private Map<Path, byte[]> desiredFiles(Report report, Path root) throws IOException { return desiredFiles(report, root, true); }

    private Map<Path, byte[]> desiredFiles(Report report, Path root, boolean preflight) throws IOException {
        LinkedHashMap<Path, byte[]> desired = new LinkedHashMap<>();
        Set<Path> destinations = new HashSet<>();
        for (FileResult file : report.files()) {
            if (!file.changesContent()) continue;
            Path before = file.beforePath() == null ? null : WorkspaceFiles.safePath(root, file.beforePath());
            Path after = file.afterPath() == null ? null : WorkspaceFiles.safePath(root, file.afterPath());
            if (preflight && before != null && (!Files.isRegularFile(before, LinkOption.NOFOLLOW_LINKS) || !WorkspaceFiles.hashFile(before).equals(file.beforeSha256())))
                throw new WorkbenchException("Input diverso dall'anteprima: " + file.beforePath());
            if (before != null) desired.put(before, null);
            if (after != null) {
                if (!destinations.add(after)) throw new WorkbenchException("Più risultati scrivono lo stesso percorso: " + file.afterPath());
                if (preflight && !after.equals(before) && Files.exists(after, LinkOption.NOFOLLOW_LINKS))
                    throw new WorkbenchException("La destinazione esiste già: " + file.afterPath());
                byte[] bytes = WorkspaceFiles.encode(file.after(), file.bom());
                if (!WorkspaceFiles.sha256(bytes).equals(file.afterSha256())) throw new WorkbenchException("Hash del risultato non coerente: " + file.afterPath());
                desired.put(after, bytes);
            }
        }
        return desired;
    }

    private static boolean isYaml(String path) { String p = path.toLowerCase(Locale.ROOT); return p.endsWith(".yaml") || p.endsWith(".yml"); }

    private static Map<String, String> beforeInventory(Report report) {
        Map<String, String> inventory = new TreeMap<>();
        for (var file : report.files()) if (file.beforePath() != null) {
            if (inventory.put(file.beforePath(), file.beforeSha256()) != null) throw new WorkbenchException("Input duplicato nell'anteprima: " + file.beforePath());
        }
        return inventory;
    }

    private static void validateReport(Report report) {
        if (report.schemaVersion() != it.migration.workbench.model.Preview.SCHEMA_VERSION)
            throw new WorkbenchException("Versione del report non supportata: " + report.schemaVersion());
        if (report.hasErrors()) throw new WorkbenchException("L'anteprima contiene errori. Correggerli e generare una nuova anteprima prima di applicare.");
        if (!WorkspaceFiles.inventoryHash(beforeInventory(report)).equals(report.inputSha256()))
            throw new WorkbenchException("Inventario dell'anteprima non coerente.");
        for (var file : report.files()) {
            if (file.changesContent() && (file.beforePath() == null && file.status() != Status.CREATED ||
                    file.afterPath() == null && file.status() != Status.DELETED ||
                    file.beforePath() != null && file.before() == null || file.afterPath() != null && file.after() == null))
                throw new WorkbenchException("Risultato incompleto o incoerente nell'anteprima.");
        }
    }
}

