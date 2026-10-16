package it.migration.workbench;

import it.migration.workbench.model.Preview.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Punto d'ingresso condiviso da CLI e futura UI. */
public final class MigrationService {
    private final GitClient git = new GitClient();

    public Report preview(Request request, Consumer<Progress> progress) throws IOException {
        Path root = WorkspaceFiles.root(request.root());
        GitSnapshot before = git.snapshot(root);
        Report result = new OpenRewriteEngine().preview(request, progress);
        if (!Objects.equals(before, git.snapshot(root))) throw new WorkbenchException("Lo stato Git è cambiato durante il dry run. Ripetere l'anteprima.");
        return new Report(result.schemaVersion(), result.toolVersion(), result.engineVersion(), result.createdAt(),
                result.root(), result.recipe(), result.resolvedRecipe(), result.configurationSha256(), result.inputSha256(), before,
                result.files(), result.diagnostics(), result.metrics());
    }

    public Report preview(Request request) throws IOException { return preview(request, event -> { }); }

    public int apply(Report report) throws IOException {
        GitSnapshot current = git.snapshot(WorkspaceFiles.root(report.root()));
        GitClient.requireSameHead(report, current);
        if (current != null && (!report.git().status().isEmpty() || !current.status().isEmpty()))
            throw new WorkbenchException("L'applicazione richiede un worktree Git pulito, sia alla preview sia adesso. Nessuna modifica eseguita.");
        return new FileApplier().apply(report);
    }
}

