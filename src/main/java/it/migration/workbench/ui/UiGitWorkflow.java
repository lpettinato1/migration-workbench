package it.migration.workbench.ui;

import it.migration.workbench.GitClient;
import it.migration.workbench.MigrationService;
import it.migration.workbench.WorkbenchException;
import it.migration.workbench.model.Preview;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Stato e precondizioni di una migrazione, indipendenti dal trasporto HTTP e dalla UI. */
public final class UiGitWorkflow {
    public enum Action { REVIEW, APPLY, COMMIT, PUSH }
    public enum Reason { CONFLICT, NOT_FOUND }
    public record OperationState(String root, String stage, long changedFiles, String commit, String branch, String remote) { }

    public static final class Rejected extends RuntimeException {
        private final Reason reason;
        public Rejected(Reason reason, String message) { super(message); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    private final LinkedHashMap<String, Operation> operations = new LinkedHashMap<>();
    private boolean reviewInvalidated;
    private boolean running;

    public UiGitWorkflow(List<Preview.Report> reports) {
        for (Preview.Report report : reports) operations.put(report.root(), new Operation(report));
    }

    public synchronized List<OperationState> snapshot() { return operations.values().stream().map(operation -> operation.state).toList(); }
    public synchronized boolean hasAppliedGit() {
        return operations.values().stream().anyMatch(operation -> operation.report.git() != null && operation.state.stage().equals("APPLIED"));
    }
    public synchronized boolean pendingPush() { return operations.values().stream().anyMatch(operation -> operation.state.stage().equals("COMMITTED")); }

    public synchronized void invalidateReview() {
        reviewInvalidated = true;
        for (Operation operation : operations.values()) {
            OperationState before = operation.state;
            if (before.stage().equals("REVIEWED")) operation.state = new OperationState(before.root(), "PREVIEW", before.changedFiles(), null, before.branch(), null);
        }
    }

    /** Git e salvataggi sono eseguiti fuori dal lock: la lettura dello stato resta disponibile. */
    public OperationState perform(Action action, Path root, List<String> reviewedPaths, String message, String remote) throws IOException {
        Operation operation;
        OperationState before;
        synchronized (this) {
            if (running) throw conflict("È già in corso un'operazione su questa migrazione.");
            operation = operations.get(root.toString());
            if (operation == null) throw new Rejected(Reason.NOT_FOUND, "La cartella non appartiene all'anteprima selezionata.");
            before = operation.state;
            if (operation.report.hasErrors()) throw conflict("L'anteprima contiene errori. Correggerli e generare una nuova anteprima.");
            if (before.changedFiles() == 0) throw conflict("L'anteprima non contiene file da modificare.");
            if (reviewInvalidated && (action == Action.REVIEW || action == Action.APPLY))
                throw conflict("Questa anteprima è stata superata da una nuova esecuzione. Rivedere l'anteprima più recente.");
            String expected = switch (action) {
                case REVIEW -> "PREVIEW";
                case APPLY -> "REVIEWED";
                case COMMIT -> "APPLIED";
                case PUSH -> "COMMITTED";
            };
            if (!before.stage().equals(expected)) throw conflict("Operazione non consentita nello stato " + before.stage() + ". Stato richiesto: " + expected + ".");
            if (action == Action.REVIEW) {
                verifyReviewedPaths(operation.report, reviewedPaths);
                operation.state = new OperationState(before.root(), "REVIEWED", before.changedFiles(), null, before.branch(), null);
                return operation.state;
            }
            if ((action == Action.COMMIT || action == Action.PUSH) && operation.report.git() == null)
                throw conflict("La cartella non è un repository Git: applicare i file conclude questa migrazione.");
            if (action == Action.APPLY && operation.report.git() != null && operation.report.git().branch().isBlank())
                throw conflict("Il repository è in HEAD detached. Preparare un branch e generare una nuova anteprima prima di applicare.");
            running = true;
        }
        try {
            OperationState after;
            switch (action) {
                case APPLY -> {
                    new MigrationService().apply(operation.report);
                    after = new OperationState(before.root(), "APPLIED", before.changedFiles(), null, before.branch(), null);
                }
                case COMMIT -> {
                    String commit = new GitClient().commit(operation.report, message);
                    after = new OperationState(before.root(), "COMMITTED", before.changedFiles(), commit, before.branch(), null);
                }
                case PUSH -> {
                    new GitClient().push(root, remote, before.branch(), before.commit());
                    after = new OperationState(before.root(), "PUSHED", before.changedFiles(), before.commit(), before.branch(), remote);
                }
                default -> throw conflict("Operazione non disponibile.");
            }
            synchronized (this) { operation.state = after; }
            return after;
        } finally { synchronized (this) { running = false; } }
    }

    private static void verifyReviewedPaths(Preview.Report report, List<String> reviewedPaths) {
        if (reviewedPaths == null) throw new WorkbenchException("Indicare i percorsi dei file verificati.");
        LinkedHashSet<String> supplied = new LinkedHashSet<>(reviewedPaths);
        if (supplied.size() != reviewedPaths.size()) throw new WorkbenchException("I percorsi verificati devono essere senza duplicati.");
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        report.files().stream().filter(Preview.FileResult::changesContent).forEach(file -> expected.add(file.afterPath() != null ? file.afterPath() : file.beforePath()));
        if (!supplied.equals(expected)) throw conflict("Verificare tutti e soli i file modificati di questa anteprima prima di confermare.");
    }

    private static Rejected conflict(String message) { return new Rejected(Reason.CONFLICT, message); }

    private static final class Operation {
        private final Preview.Report report;
        private OperationState state;
        private Operation(Preview.Report report) {
            this.report = report;
            state = new OperationState(report.root(), "PREVIEW", report.changedFiles(), null, report.git() == null ? null : report.git().branch(), null);
        }
    }
}
