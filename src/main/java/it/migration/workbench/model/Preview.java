package it.migration.workbench.model;

import java.util.List;
import java.util.Map;

/** Contratto applicativo: non dipende da CLI, frontend o classi OpenRewrite. */
public final class Preview {
    public static final int SCHEMA_VERSION = 1;
    public static final String TOOL_VERSION = "0.3.0";
    public static final String ENGINE_VERSION = "8.90.4";

    private Preview() { }

    public record Request(String root, String rules, String recipe, Map<String, String> parameters) {
        public Request {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public enum Status { UNCHANGED, MODIFIED, CREATED, DELETED, RENAMED, SKIPPED, ERROR }
    public enum Severity { INFO, WARNING, ERROR }
    public record Diagnostic(Severity severity, String code, String path, String message) { }
    public record GitSnapshot(String head, String branch, String status) { }
    public record Metrics(int discoveredFiles, long parseMillis, long executionMillis, long totalMillis) { }
    public record Progress(String phase, String path, String message) { }

    public record FileResult(String beforePath, String afterPath, Status status,
                             String before, String after, String beforeSha256, String afterSha256,
                             boolean bom, List<String> recipes, String diff) {
        public FileResult {
            recipes = recipes == null ? List.of() : List.copyOf(recipes);
        }

        public boolean changesContent() {
            return status == Status.MODIFIED || status == Status.CREATED ||
                   status == Status.DELETED || status == Status.RENAMED;
        }
    }

    public record Report(int schemaVersion, String toolVersion, String engineVersion, String createdAt,
                         String root, String recipe, String resolvedRecipe, String configurationSha256,
                         String inputSha256, GitSnapshot git, List<FileResult> files,
                         List<Diagnostic> diagnostics, Metrics metrics) {
        public Report {
            files = List.copyOf(files);
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
        }

        public long changedFiles() {
            return files.stream().filter(FileResult::changesContent).count();
        }
    }
}

