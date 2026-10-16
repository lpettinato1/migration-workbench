package it.migration.workbench;

import it.migration.workbench.model.Preview.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApplyTest extends TestWorkspace {
    @ParameterizedTest @ValueSource(strings = {"edit-condition", "new-file", "delete-condition"})
    void changedReadOnlyContextInvalidatesPreviewBeforeAnyWrite(String mutation) throws Exception {
        Path root = fixture("stale", true);
        Report report = service.preview(request(root));
        switch (mutation) {
            case "edit-condition" -> write(root, "config/application.yaml", "security:\n  enabled: false\n");
            case "new-file" -> write(root, "new.yaml", "added: true\n");
            case "delete-condition" -> Files.delete(root.resolve("config/application.yaml"));
        }
        Map<String, String> current = WorkspaceFiles.inventory(root);
        assertThrows(WorkbenchException.class, () -> service.apply(report));
        assertEquals(current, WorkspaceFiles.inventory(root));
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ioFailureRestoresAlreadyWrittenContents(boolean failAfterSecondWrite) throws Exception {
        Path root = fixture("rollback", true);
        write(root, "deploy/Other-ENV/values.yaml", SIMPLE);
        Report report = service.preview(request(root));
        assertEquals(2, report.changedFiles());
        Map<String, String> before = WorkspaceFiles.inventory(root);
        AtomicInteger calls = new AtomicInteger();
        FileApplier applier = new FileApplier((path, bytes) -> {
            boolean second = calls.incrementAndGet() == 2;
            if (second && !failAfterSecondWrite) throw new IOException("Guasto I/O simulato prima della seconda scrittura");
            WorkspaceFiles.atomicWrite(path, bytes);
            if (second) throw new IOException("Guasto I/O simulato dopo la seconda scrittura");
        });
        WorkbenchException failure = assertThrows(WorkbenchException.class, () -> applier.apply(report));
        assertTrue(failure.getMessage().contains("ripristino completato"), failure.getMessage());
        assertEquals(before, WorkspaceFiles.inventory(root));
    }

    @Test void pathTraversalInAReportIsRejectedBeforeAnyWrite() throws Exception {
        Path root = fixture("escape", true);
        Report report = service.preview(request(root));
        List<FileResult> files = new ArrayList<>(report.files());
        int i = files.indexOf(file(report, "deploy/Blue_42/values.yaml"));
        FileResult old = files.get(i);
        files.set(i, new FileResult(old.beforePath(), "../escaped.yaml", Status.RENAMED, old.before(), old.after(),
                old.beforeSha256(), old.afterSha256(), old.bom(), old.recipes(), old.diff()));
        Report malicious = withFiles(report, files);
        assertThrows(WorkbenchException.class, () -> service.apply(malicious));
        assertFalse(Files.exists(temporary.resolve("escaped.yaml")));
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void outputHashCannotBeChangedWithoutChangingTheApprovedResult() throws Exception {
        Path root = fixture("hash", true);
        Report report = service.preview(request(root));
        FileResult change = file(report, "deploy/Blue_42/values.yaml");
        List<FileResult> files = report.files().stream().map(f -> f == change ? new FileResult(f.beforePath(), f.afterPath(), f.status(),
                f.before(), "tampered: true\n", f.beforeSha256(), f.afterSha256(), f.bom(), f.recipes(), f.diff()) : f).toList();
        assertThrows(WorkbenchException.class, () -> service.apply(withFiles(report, files)));
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void creationDeletionAndRenameAreAppliedAndIdempotent() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("file-operations"));
        write(root, "old.yaml", "old: true\n");
        write(root, "rename.yaml", "keep: exact\n");
        Path rules = write(temporary, "files.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: it.Files
                displayName: File operations
                description: Create, delete, rename.
                recipeList:
                  - org.openrewrite.yaml.CreateYamlFile:
                      relativeFileName: generated.yaml
                      fileContents: 'created: true'
                      overwriteExisting: false
                  - org.openrewrite.DeleteSourceFiles:
                      filePattern: old.yaml
                  - org.openrewrite.RenameFile:
                      fileMatcher: rename.yaml
                      fileName: renamed.yaml
                """);
        Request request = new Request(root.toString(), rules.toString(), "it.Files", Map.of());
        Report report = service.preview(request);
        assertFalse(report.hasErrors(), report.diagnostics().toString());
        assertEquals(Set.of(Status.CREATED, Status.DELETED, Status.RENAMED), new HashSet<>(report.files().stream().map(FileResult::status).toList()));
        assertEquals(3, service.apply(report));
        assertFalse(Files.exists(root.resolve("old.yaml")));
        assertFalse(Files.exists(root.resolve("rename.yaml")));
        assertEquals("keep: exact\n", Files.readString(root.resolve("renamed.yaml")));
        assertEquals("created: true", Files.readString(root.resolve("generated.yaml")));
        assertEquals(0, service.preview(request).changedFiles());
    }

    private Report withFiles(Report report, List<FileResult> files) {
        return new Report(report.schemaVersion(), report.toolVersion(), report.engineVersion(), report.createdAt(), report.root(),
                report.recipe(), report.resolvedRecipe(), report.configurationSha256(), report.inputSha256(), report.git(), files, report.diagnostics(), report.metrics());
    }
}

