package it.migration.workbench;

import it.migration.workbench.model.Preview.GitSnapshot;
import it.migration.workbench.model.Preview.Report;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Git nativo, senza shell: argomenti e pathspec non diventano comandi eseguibili. */
public final class GitClient {
    public record Output(int exitCode, String text) { }
    public record Details(List<String> branches, List<String> remotes, GitSnapshot git) { }

    /** Elenca esclusivamente ref e remote già presenti; non contatta il server Git. */
    public Details details(Path directory) throws IOException {
        Path root = WorkspaceFiles.root(directory.toString());
        GitSnapshot git = snapshot(root);
        if (git == null) return new Details(List.of(), List.of(), null);
        String refs = checked(root, "for-each-ref", "--format=%(refname:short)%00%(symref)", "refs/heads", "refs/remotes");
        List<String> branches = new ArrayList<>();
        for (String line : refs.lines().toList()) {
            int separator = line.indexOf('\0');
            if (separator > 0 && line.substring(separator + 1).isEmpty()) branches.add(line.substring(0, separator));
        }
        return new Details(List.copyOf(branches), checked(root, "remote").lines().filter(line -> !line.isBlank()).toList(), git);
    }

    public GitSnapshot snapshot(Path root) throws IOException {
        if (!Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return null;
        String head = checked(root, "rev-parse", "--verify", "HEAD").strip();
        Output symbolic = run(root, List.of("symbolic-ref", "--quiet", "--short", "HEAD"), null);
        if (symbolic.exitCode() != 0 && symbolic.exitCode() != 1) fail(symbolic);
        String status = checked(root, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        return new GitSnapshot(head, symbolic.exitCode() == 0 ? symbolic.text().strip() : "", status);
    }

    public Path prepare(Path repository, String source, String branch, Path destination) throws IOException {
        Path root = WorkspaceFiles.root(repository.toString());
        GitSnapshot snapshot = snapshot(root);
        if (snapshot == null) throw new WorkbenchException("La cartella non è la root di un repository Git: " + root);
        if (!snapshot.status().isEmpty()) throw new WorkbenchException("Il checkout di origine contiene modifiche. In questo incremento la preparazione richiede un checkout pulito; non è stato modificato.");
        validateBranch(root, branch);
        if (source == null || source.isBlank() || source.startsWith("-")) throw new WorkbenchException("Branch/ref di partenza non valido.");
        String commit = checked(root, "rev-parse", "--verify", "--end-of-options", source + "^{commit}").strip();
        Output exists = run(root, List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch), null);
        if (exists.exitCode() == 0) throw new WorkbenchException("Il branch '" + branch + "' esiste già. Nessun riutilizzo implicito.");
        if (exists.exitCode() != 1) fail(exists);
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new WorkbenchException("La cartella del nuovo worktree esiste già: " + target);
        if (target.startsWith(root) || root.startsWith(target)) throw new WorkbenchException("Il worktree deve essere esterno al checkout di origine.");
        Files.createDirectories(target.getParent());
        Path actualParent = target.getParent().toRealPath();
        if (actualParent.startsWith(root)) throw new WorkbenchException("La destinazione del worktree attraversa il checkout di origine.");
        checked(root, "worktree", "add", "-b", branch, target.toString(), commit);
        return target.toRealPath();
    }

    public String commit(Report report, String message) throws IOException {
        Path root = WorkspaceFiles.root(report.root());
        if (message == null || message.isBlank()) throw new WorkbenchException("Specificare un messaggio di commit.");
        GitSnapshot current = snapshot(root);
        if (current == null) throw new WorkbenchException("Il commit richiede la root di un repository Git.");
        requireSameHead(report, current);
        if (current.branch().isEmpty()) throw new WorkbenchException("Il checkout è detached; creare prima il branch della migrazione.");
        new FileApplier().verifyApplied(report);
        if (!checked(root, "diff", "--cached", "--name-only", "-z").isEmpty())
            throw new WorkbenchException("L'indice Git contiene già file in staging. Nessun commit automatico.");
        Set<String> allowed = new TreeSet<>();
        report.files().stream().filter(f -> f.changesContent()).forEach(f -> {
            if (f.beforePath() != null) allowed.add(f.beforePath());
            if (f.afterPath() != null) allowed.add(f.afterPath());
        });
        if (allowed.isEmpty()) throw new WorkbenchException("L'anteprima non contiene modifiche da committare.");
        String changed = checked(root, "diff", "--name-only", "-z") + checked(root, "ls-files", "--others", "--exclude-standard", "-z");
        for (String path : changed.split("\0")) {
            if (!path.isEmpty() && !allowed.contains(path)) throw new WorkbenchException("Modifica estranea alla migrazione: " + path + ". Il commit non è stato eseguito.");
        }
        byte[] pathspecs = (String.join("\0", allowed) + "\0").getBytes(StandardCharsets.UTF_8);
        Output add = run(root, List.of("add", "--pathspec-from-file=-", "--pathspec-file-nul"), pathspecs);
        if (add.exitCode() != 0) fail(add);
        String staged = checked(root, "diff", "--cached", "--raw", "--no-abbrev", "--no-renames", "-z");
        for (String path : checked(root, "diff", "--cached", "--name-only", "--no-renames", "-z").split("\0")) {
            if (!path.isEmpty() && !allowed.contains(path))
                throw new WorkbenchException("L'indice contiene nuove modifiche estranee. Nessun commit eseguito; lo staging è stato mantenuto.");
        }
        Output commit = run(root, List.of("commit", "-m", message), null);
        if (commit.exitCode() != 0) {
            String recovery;
            try {
                requireSameHead(report, snapshot(root));
                if (!staged.equals(checked(root, "diff", "--cached", "--raw", "--no-abbrev", "--no-renames", "-z")))
                    throw new WorkbenchException("L'indice è cambiato durante il commit.");
                Output reset = run(root, List.of("reset", "--quiet", report.git().head(), "--pathspec-from-file=-", "--pathspec-file-nul"), pathspecs);
                if (reset.exitCode() != 0) fail(reset);
                recovery = "Lo staging di questa operazione è stato rimosso. I contenuti dei file sono rimasti intatti.";
            } catch (IOException | WorkbenchException failure) {
                recovery = "Non è stato possibile confermare il ripristino dello staging: " + failure.getMessage() + " Verificare lo stato Git prima di riprovare.";
            }
            fail(new Output(commit.exitCode(), commit.text().strip() + "\n" + recovery));
        }
        return checked(root, "rev-parse", "HEAD").strip();
    }

    public void push(Path root, String remote, String branch, String expectedCommit) throws IOException {
        root = WorkspaceFiles.root(root.toString());
        validateBranch(root, branch);
        if (remote == null || !remote.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) throw new WorkbenchException("Indicare il nome di un remote Git configurato, non un URL.");
        if (checked(root, "remote").lines().noneMatch(remote::equals))
            throw new WorkbenchException("Il remote Git '" + remote + "' non è configurato nel repository.");
        GitSnapshot current = snapshot(root);
        if (current == null || !current.branch().equals(branch) || !current.head().equals(expectedCommit))
            throw new WorkbenchException("Branch o commit corrente diverso da quello richiesto per il push.");
        if (!current.status().isEmpty()) throw new WorkbenchException("Il worktree contiene modifiche: ricontrollare prima del push.");
        checked(root, "push", remote, "HEAD:refs/heads/" + branch);
    }

    static void requireSameHead(Report report, GitSnapshot current) {
        GitSnapshot expected = report.git();
        if (expected == null && current == null) return;
        if (expected == null || current == null || !expected.head().equals(current.head()) || !expected.branch().equals(current.branch()))
            throw new WorkbenchException("HEAD o branch è cambiato dall'anteprima. Generare una nuova anteprima.");
    }

    private void validateBranch(Path root, String branch) throws IOException {
        if (branch == null || branch.isBlank() || branch.startsWith("-")) throw new WorkbenchException("Nome del branch non valido.");
        checked(root, "check-ref-format", "--branch", branch);
    }

    public String checked(Path root, String... arguments) throws IOException {
        Output output = run(root, List.of(arguments), null);
        if (output.exitCode() != 0) fail(output);
        return output.text();
    }

    private static void fail(Output output) {
        throw new WorkbenchException("Git ha interrotto l'operazione (codice " + output.exitCode() + "): " + output.text().strip());
    }

    Output run(Path root, List<String> arguments, byte[] stdin) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "-C", root.toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_LITERAL_PATHSPECS", "1");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        AtomicBoolean truncated = new AtomicBoolean();
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            try (var stream = process.getInputStream()) {
                int limit = 8 * 1024 * 1024;
                byte[] prefix = stream.readNBytes(limit + 1);
                truncated.set(prefix.length > limit);
                bytes.write(prefix, 0, Math.min(prefix.length, limit));
                stream.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) { readFailure.set(e); }
        });
        try {
            try (var stream = process.getOutputStream()) { if (stdin != null) stream.write(stdin); }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new WorkbenchException("Git ha superato il tempo massimo di 60 secondi. Verificare lo stato del repository prima di riprovare.");
            }
            reader.join(5000);
            if (reader.isAlive()) throw new WorkbenchException("L'output Git non è terminato dopo la chiusura del processo.");
            if (readFailure.get() != null) throw readFailure.get();
            if (truncated.get()) throw new WorkbenchException("L'output Git supera 8 MiB e non può essere verificato integralmente. Verificare lo stato del repository.");
            return new Output(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new WorkbenchException("Operazione Git interrotta.", e);
        }
    }
}

