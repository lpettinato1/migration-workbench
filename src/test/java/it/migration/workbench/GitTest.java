package it.migration.workbench;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class GitTest extends TestWorkspace {
    private final GitClient git = new GitClient();

    Path repository(String name) throws Exception {
        Path root = fixture(name, true);
        git.checked(root, "init", "-b", "main");
        git.checked(root, "config", "user.name", "Migration Tests");
        git.checked(root, "config", "user.email", "migration-tests@example.invalid");
        git.checked(root, "config", "commit.gpgsign", "false");
        git.checked(root, "config", "core.autocrlf", "false");
        git.checked(root, "config", "core.hooksPath", temporary.resolve("no-hooks").toString());
        git.checked(root, "add", ".");
        git.checked(root, "commit", "-m", "Initial fixture");
        return root;
    }

    @Test void prepareCreatesSeparateWorktreeAndRejectsExistingBranch() throws Exception {
        Path root = repository("source");
        var before = git.snapshot(root);
        Path target = git.prepare(root, "main", "migration/test", temporary.resolve("worktree"));
        assertEquals("migration/test", git.snapshot(target).branch());
        assertEquals(before, git.snapshot(root));
        assertThrows(WorkbenchException.class, () -> git.prepare(root, "main", "migration/test", temporary.resolve("another")));
        assertFalse(Files.exists(temporary.resolve("another")));
    }

    @Test void dirtyOriginDoesNotChangeOrCreateBranch() throws Exception {
        Path root = repository("dirty");
        write(root, "untracked.txt", "do not include");
        assertThrows(WorkbenchException.class, () -> git.prepare(root, "main", "migration/test", temporary.resolve("new")));
        assertEquals("main", git.snapshot(root).branch());
        assertEquals("do not include", Files.readString(root.resolve("untracked.txt")));
        assertFalse(Files.exists(temporary.resolve("new")));
    }

    @Test void changingBranchOrHeadInvalidatesTheApprovedPreview() throws Exception {
        Path root = repository("head");
        var report = service.preview(request(root));
        git.checked(root, "checkout", "-b", "other");
        assertThrows(WorkbenchException.class, () -> service.apply(report));
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void commitRejectsUnrelatedChangesAndExistingStaging() throws Exception {
        Path root = repository("commit");
        var report = service.preview(request(root));
        service.apply(report);
        write(root, "unrelated.txt", "external");
        assertThrows(WorkbenchException.class, () -> git.commit(report, "Migration"));
        assertEquals(report.git().head(), git.snapshot(root).head());
        assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
        Files.delete(root.resolve("unrelated.txt"));
        git.checked(root, "add", "deploy/Blue_42/values.yaml");
        String staged = git.checked(root, "diff", "--cached", "--raw", "--no-abbrev", "-z");
        assertThrows(WorkbenchException.class, () -> git.commit(report, "Migration"));
        assertEquals(staged, git.checked(root, "diff", "--cached", "--raw", "--no-abbrev", "-z"));
    }

    @Test void rejectedCommitRestoresOnlyItsStagingAndAllowsRetryWithoutChangingFiles() throws Exception {
        Path root = repository("rejected-commit");
        var report = service.preview(request(root));
        service.apply(report);
        var appliedFiles = WorkspaceFiles.inventory(root);
        git.checked(root, "config", "user.name", "");
        git.checked(root, "config", "user.email", "");

        WorkbenchException failure = assertThrows(WorkbenchException.class, () -> git.commit(report, "Migration"));
        assertTrue(failure.getMessage().contains("Lo staging di questa operazione è stato rimosso"), failure.getMessage());
        assertEquals(report.git().head(), git.snapshot(root).head());
        assertTrue(git.checked(root, "diff", "--cached", "--name-only").isEmpty());
        assertEquals(appliedFiles, WorkspaceFiles.inventory(root));
        assertEquals(EXPECTED, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));

        git.checked(root, "config", "user.name", "Migration Tests");
        git.checked(root, "config", "user.email", "migration-tests@example.invalid");
        String commit = git.commit(report, "Migration after identity correction");
        assertNotEquals(report.git().head(), commit);
        assertEquals(commit, git.snapshot(root).head());
        assertTrue(git.snapshot(root).status().isEmpty());
        assertEquals(appliedFiles, WorkspaceFiles.inventory(root));
    }

    @Test void applyRejectsDirtyGitEvenForUnrelatedNonYamlChanges() throws Exception {
        Path root = repository("apply-dirty");
        var report = service.preview(request(root));
        write(root, "external.txt", "outside YAML inventory");
        assertThrows(WorkbenchException.class, () -> service.apply(report));
        assertEquals(SIMPLE, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void pushRejectsAnUnexpectedCommitBeforeContactingTheRemote() throws Exception {
        Path root = repository("push");
        assertThrows(WorkbenchException.class, () -> git.push(root, "origin", "main", "not-the-current-commit"));
        assertThrows(WorkbenchException.class, () -> git.prepare(root, "main", "--bad", temporary.resolve("worktree")));
    }
}
