package it.migration.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.migration.workbench.model.Preview.*;
import it.migration.workbench.model.Preview;
import it.migration.workbench.ui.LocalUiServer;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.awt.Desktop;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "workbench", mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Migrazioni YAML standalone. Preview, applicazione e Git sono operazioni esplicite.",
        subcommands = {Main.PreviewCommand.class, Main.ApplyCommand.class, Main.PrepareCommand.class,
                Main.CommitCommand.class, Main.PushCommand.class, Main.DescribeCommand.class, Main.UiCommand.class})
public final class Main implements Runnable {
    @Spec CommandSpec spec;

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((failure, commandLine, parseResult) -> {
            commandLine.getErr().println("ERRORE: " + failure.getMessage());
            commandLine.getErr().flush();
            return 1;
        });
        System.exit(cli.execute(args.length == 0 ? new String[] {"ui"} : args));
    }

    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }

    static final class Version implements IVersionProvider {
        @Override public String[] getVersion() {
            return new String[] {"Migration Workbench " + Preview.TOOL_VERSION + " · Java " + Runtime.version().feature() + " · OpenRewrite " + Preview.ENGINE_VERSION};
        }
    }

    @Command(name = "ui", mixinStandardHelpOptions = true, description = "Avvia l'interfaccia locale per scrivere ricette e rivedere le anteprime.")
    static final class UiCommand implements Callable<Integer> {
        @Option(names = "--no-browser", description = "Mostra il collegamento senza aprire il browser.") boolean noBrowser;
        @Option(names = "--port", defaultValue = "0", description = "Porta locale; 0 seleziona automaticamente una porta libera.") int port;
        @Option(names = "--directory", description = "Cartella iniziale del selettore.") Path directory;
        @Override public Integer call() throws Exception {
            Path base = directory == null ? Path.of("").toAbsolutePath() : directory;
            try (LocalUiServer server = LocalUiServer.start(base, port)) {
                System.out.println("Migration Workbench: " + server.launchUri());
                System.out.println("L'interfaccia resta disponibile finché questa finestra è aperta. Per terminare: Ctrl+C.");
                System.out.flush();
                if (!noBrowser) {
                    try {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                            Desktop.getDesktop().browse(server.launchUri());
                        else System.err.println("Aprire nel browser il collegamento indicato sopra.");
                    } catch (Exception failure) { System.err.println("Apertura automatica non disponibile. Aprire il collegamento indicato sopra."); }
                }
                server.await();
                return 0;
            }
        }
    }

    static final class Rules {
        @Option(names = "--rules", required = true, description = "File YAML delle ricette, esterno alla root da migrare.") Path rules;
        @Option(names = "--recipe", required = true, description = "Nome completo della ricetta da attivare.") String recipe;
        @Option(names = "--param", description = "Parametro migration.nome=valore; ripetibile.") Map<String, String> parameters = new LinkedHashMap<>();
    }

    @Command(name = "preview", mixinStandardHelpOptions = true, description = "Calcola la migrazione senza scrivere nei file originali.")
    static final class PreviewCommand implements Callable<Integer> {
        @Option(names = "--root", required = true) Path root;
        @Mixin Rules rules;
        @Option(names = "--report", required = true, description = "Report JSON da salvare fuori dalla root.") Path report;
        @Option(names = "--diff", description = "Mostra anche la patch dei file modificati.") boolean diff;
        @Override public Integer call() throws Exception {
            Report result = new MigrationService().preview(new Request(root.toString(), rules.rules.toString(), rules.recipe, rules.parameters), progress -> {
                if (progress.phase().equals("REWRITE")) System.err.println(progress.message());
            });
            new ReportStore().write(report, result);
            for (Diagnostic diagnostic : result.diagnostics())
                System.err.printf("%s [%s]%s %s%n", diagnostic.severity(), diagnostic.code(),
                        diagnostic.path() == null ? "" : " " + diagnostic.path() + ":", diagnostic.message());
            System.out.printf("Anteprima: %d file, %d modifiche, %d ignorati, %d errori. Originali non modificati.%n",
                    result.metrics().discoveredFiles(), result.changedFiles(),
                    result.files().stream().filter(f -> f.status() == Status.SKIPPED).count(),
                    result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).count());
            System.out.printf("Tempi: parsing %d ms; ricette %d ms; totale %d ms.%n", result.metrics().parseMillis(), result.metrics().executionMillis(), result.metrics().totalMillis());
            System.out.println("Report: " + report.toAbsolutePath().normalize());
            if (diff) result.files().stream().filter(FileResult::changesContent).forEach(f -> System.out.print(f.diff()));
            return result.hasErrors() ? 2 : 0;
        }
    }

    @Command(name = "apply", mixinStandardHelpOptions = true, description = "Applica esattamente un'anteprima dopo averne verificato input e stato Git.")
    static final class ApplyCommand implements Callable<Integer> {
        @Option(names = "--report", required = true) Path report;
        @Override public Integer call() throws Exception {
            int files = new MigrationService().apply(new ReportStore().read(report));
            System.out.println("Applicazione completata: " + files + " file modificati. Nessun commit o push eseguito.");
            return 0;
        }
    }

    @Command(name = "prepare", mixinStandardHelpOptions = true, description = "Crea un branch nuovo in un worktree esterno; non cambia branch nel checkout di origine.")
    static final class PrepareCommand implements Callable<Integer> {
        @Option(names = "--repository", required = true) Path repository;
        @Option(names = "--source", required = true) String source;
        @Option(names = "--branch", required = true) String branch;
        @Option(names = "--worktree", required = true) Path worktree;
        @Override public Integer call() throws Exception {
            System.out.println(new GitClient().prepare(repository, source, branch, worktree));
            return 0;
        }
    }

    @Command(name = "commit", mixinStandardHelpOptions = true, description = "Committa i soli percorsi dell'anteprima applicata, senza modifiche estranee.")
    static final class CommitCommand implements Callable<Integer> {
        @Option(names = "--report", required = true) Path report;
        @Option(names = "--message", required = true) String message;
        @Override public Integer call() throws Exception {
            System.out.println(new GitClient().commit(new ReportStore().read(report), message));
            return 0;
        }
    }

    @Command(name = "push", mixinStandardHelpOptions = true, description = "Invia il commit richiesto a un remote configurato, senza force push.")
    static final class PushCommand implements Callable<Integer> {
        @Option(names = "--root", required = true) Path root;
        @Option(names = "--remote", required = true) String remote;
        @Option(names = "--branch", required = true) String branch;
        @Option(names = "--commit", required = true, description = "SHA esatto del commit da inviare.") String commit;
        @Override public Integer call() throws Exception {
            new GitClient().push(root, remote, branch, commit);
            System.out.println("Push completato sul branch " + branch + ".");
            return 0;
        }
    }

    @Command(name = "describe", mixinStandardHelpOptions = true, description = "Mostra metadati, opzioni e composizione della ricetta caricata.")
    static final class DescribeCommand implements Callable<Integer> {
        @Mixin Rules rules;
        @Override public Integer call() throws Exception {
            var loaded = new RecipeCatalog().load(rules.rules.toRealPath(), rules.recipe, rules.parameters);
            System.out.println(new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(loaded.recipe().getDescriptor()));
            return 0;
        }
    }
}

