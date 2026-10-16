package it.migration.workbench;

import it.migration.workbench.model.Preview.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest extends TestWorkspace {
    @Test void complexGoldenAndDryRunDoNotChangeInputs() throws Exception {
        Path root = fixture("complex", true);
        write(root, "deploy/Blue_42/values.yaml", resource("/golden/complex-before.yaml"));
        write(root, "outside/values.yaml", SIMPLE);
        Map<String, String> before = WorkspaceFiles.inventory(root);
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(resource("/golden/complex-after.yaml"), file(result, "deploy/Blue_42/values.yaml").after());
        assertEquals(SIMPLE, file(result, "outside/values.yaml").after());
        assertEquals(before, WorkspaceFiles.inventory(root));
        assertTrue(file(result, "deploy/Blue_42/values.yaml").diff().contains("-  endpoint: \"old-service\""));
        assertTrue(file(result, "deploy/Blue_42/values.yaml").recipes().contains("it.migration.workbench.recipes.ChangeValueWhenPropertyEquals"));
    }

    @Test void applyAndSecondRunAreIdempotent() throws Exception {
        Path root = fixture("idempotent", true);
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(1, service.apply(result));
        assertEquals(EXPECTED, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
        Report second = service.preview(request(root));
        assertFalse(second.hasErrors(), second.diagnostics().toString());
        assertEquals(0, second.changedFiles());
    }

    @Test void scannerSeesConditionOutsideTheModificationFilterAndIsIsolatedBetweenRoots() throws Exception {
        Path yes = fixture("condition-yes", true), no = fixture("condition-no", false);
        Report first = service.preview(request(yes));
        Report second = service.preview(request(no));
        assertEquals(EXPECTED, file(first, "deploy/Blue_42/values.yaml").after());
        assertEquals("app:\n  endpoint: new-service\n  timeout: 30s\ncredentials:\n  enabled: false\n", file(second, "deploy/Blue_42/values.yaml").after());
        assertEquals(Status.UNCHANGED, file(first, "config/application.yaml").status());
        assertEquals(Status.UNCHANGED, file(second, "config/application.yaml").status());
    }

    @Test void missingConditionDoesNotInventAMatch() throws Exception {
        Path root = fixture("no-condition", true);
        Files.delete(root.resolve("config/application.yaml"));
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(file(result, "deploy/Blue_42/values.yaml").after().contains("enabled: false"));
    }

    @Test void crlfAndUtf8BomSurviveActualApply() throws Exception {
        Path root = fixture("crlf", true);
        Path path = root.resolve("deploy/Blue_42/values.yaml");
        Files.write(path, WorkspaceFiles.encode(SIMPLE.replace("\n", "\r\n"), true));
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        service.apply(result);
        assertArrayEquals(WorkspaceFiles.encode(EXPECTED.replace("\n", "\r\n"), true), Files.readAllBytes(path));
    }

    @Test void helmIsSkippedButQuotedTemplateAndCommentsArePreserved() throws Exception {
        Path root = fixture("helm", true);
        String helm = "metadata:\n  name: {{ .Release.Name }}\n";
        write(root, "helm/templates/config.yaml", helm);
        write(root, "quoted.yaml", "value: \"{{ .Values.keep }}\"\n# {{ comment }}\n");
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(Status.SKIPPED, file(result, "helm/templates/config.yaml").status());
        assertEquals(Status.UNCHANGED, file(result, "quoted.yaml").status());
        service.apply(result);
        assertEquals(helm, Files.readString(root.resolve("helm/templates/config.yaml")));
    }

    @Test void invalidYamlBlocksApplicationInsteadOfBeingReportedAsUnchanged() throws Exception {
        Path root = fixture("invalid", true);
        write(root, "broken.yaml", "a: [broken\n");
        Map<String, String> hashes = WorkspaceFiles.inventory(root);
        Report result = service.preview(request(root));
        assertTrue(result.hasErrors());
        assertEquals(Status.ERROR, file(result, "broken.yaml").status());
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals(hashes, WorkspaceFiles.inventory(root));
    }

    @Test void emptyRootHasNoInventedChanges() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("empty"));
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(0, result.changedFiles());
        assertEquals(0, result.metrics().discoveredFiles());
    }

    @Test void unsupportedEncodingRemainsUntouchedAndVisible() throws Exception {
        Path root = fixture("encoding", true);
        byte[] data = SIMPLE.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[data.length + 2]; bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xfe;
        System.arraycopy(data, 0, bytes, 2, data.length);
        Files.write(root.resolve("utf16.yaml"), bytes);
        Report result = service.preview(request(root));
        assertEquals(Status.SKIPPED, file(result, "utf16.yaml").status());
        service.apply(result);
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("utf16.yaml")));
    }

    @Test void recipeParametersAreResolvedAsYamlValuesNotInjectedAsSyntax() throws Exception {
        String unusual = "a: b # comment\n'quoted' \"double\" $1 \\path";
        var loaded = new RecipeCatalog().load(RULES, RECIPE, Map.of("migration.endpoint", unusual));
        Map<?, ?> document = (Map<?, ?>) new Yaml().loadAll(loaded.yaml()).iterator().next();
        assertEquals(RECIPE, document.get("name"));
        assertTrue(loaded.yaml().contains("migration.endpoint") == false);
        List<Object> docs = new ArrayList<>(); new Yaml().loadAll(loaded.yaml()).forEach(docs::add);
        Map<?, ?> valuesRecipe = (Map<?, ?>) docs.get(1);
        Map<?, ?> change = (Map<?, ?>) ((Map<?, ?>) ((List<?>) valuesRecipe.get("recipeList")).getFirst()).get("org.openrewrite.yaml.ChangePropertyValue");
        assertEquals(unusual, change.get("newValue"));
    }

    @ParameterizedTest @ValueSource(strings = {"NoSuchRecipe", "org.openrewrite.yaml.NoSuchRecipe"})
    void unknownRecipeIsRejected(String name) throws Exception {
        Path root = fixture("unknown", true);
        assertThrows(WorkbenchException.class, () -> service.preview(new Request(root.toString(), RULES.toString(), name, Map.of())));
    }

    @Test void missingParameterAndInvalidRecipeOptionsAreRejected() throws Exception {
        Path root = fixture("options", true);
        Path rules = write(temporary, "missing.yml", "type: specs.openrewrite.org/v1beta/recipe\nname: it.Test\ndisplayName: Test\ndescription: Test.\nrecipeList:\n  - org.openrewrite.yaml.ChangePropertyValue:\n      propertyKey: app.endpoint\n      newValue: '${migration.required}'\n");
        assertThrows(WorkbenchException.class, () -> service.preview(new Request(root.toString(), rules.toString(), "it.Test", Map.of())));
        Files.writeString(rules, Files.readString(rules).replace("${migration.required}", "value") + "      regex: true\n");
        assertThrows(WorkbenchException.class, () -> service.preview(new Request(root.toString(), rules.toString(), "it.Test", Map.of())));
    }

    @Test void reportRoundTripKeepsSnapshotAndChanges() throws Exception {
        Path root = fixture("report", true);
        Report result = service.preview(request(root));
        Path path = temporary.resolve("report.json");
        new ReportStore().write(path, result);
        Report read = new ReportStore().read(path);
        assertEquals(result, read);
        assertThrows(WorkbenchException.class, () -> new ReportStore().write(root.resolve("report.json"), result));
        assertThrows(WorkbenchException.class, () -> new ReportStore().write(root.resolve("new/report.json"), result));
        assertFalse(Files.exists(root.resolve("new")));
    }

    @Test void nestedGitRepositoryIsNotMigratedAsPartOfItsParent() throws Exception {
        Path root = fixture("nested", true);
        write(root, "child/.git", "gitdir: somewhere");
        write(root, "child/deploy/env/values.yaml", SIMPLE);
        Report result = service.preview(request(root));
        assertEquals(2, result.metrics().discoveredFiles());
        assertEquals(SIMPLE, Files.readString(root.resolve("child/deploy/env/values.yaml")));
    }

    @Test void scalarAnchorAndCircularAliasStayReferences() throws Exception {
        Path root = fixture("alias-graph", true);
        String before = "app:\n  endpoint: &host old-service\ncopy: *host\ncycle: &cycle\n  self: *cycle\n";
        write(root, "deploy/Blue_42/values.yaml", before);
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals("app:\n  endpoint: &host new-service\ncopy: *host\ncycle: &cycle\n  self: *cycle\n", file(result, "deploy/Blue_42/values.yaml").after());
        service.apply(result);
        assertEquals(0, service.preview(request(root)).changedFiles());
    }

    @Test void yamlSetAndNumericKeysArePreservedInAnEditedDocument() throws Exception {
        Path root = fixture("types", true);
        String before = "app:\n  endpoint: old-service\nset: !!set\n  admin: null\n  reader: null\n123: valore\n";
        write(root, "deploy/Blue_42/values.yaml", before);
        Report result = service.preview(request(root));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals("app:\n  endpoint: new-service\nset: !!set\n  admin: null\n  reader: null\n123: valore\n", file(result, "deploy/Blue_42/values.yaml").after());
    }

    @Test void malformedRecipeOutputCannotBeApplied() throws Exception {
        Path root = fixture("invalid-output", true);
        write(root, "deploy/Blue_42/values.yaml", "app:\n  endpoint: \"old-service\"\n");
        Request request = new Request(root.toString(), RULES.toString(), RECIPE, Map.of("migration.endpoint", "unescaped\"quote"));
        Report result = service.preview(request);
        assertTrue(result.hasErrors(), "Il controllo deve intercettare l'output non quotato correttamente della ricetta nativa.");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("RECIPE_EXECUTION_ERROR")));
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals("app:\n  endpoint: \"old-service\"\n", Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void recipeOrderIsPreserved() throws Exception {
        Path root = fixture("order", true);
        Path rules = write(temporary, "ordered.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: it.TestOrder
                displayName: Order
                description: Order.
                recipeList:
                  - org.openrewrite.yaml.ChangePropertyValue:
                      propertyKey: app.endpoint
                      oldValue: old-service
                      newValue: middle
                  - org.openrewrite.yaml.ChangePropertyValue:
                      propertyKey: app.endpoint
                      oldValue: middle
                      newValue: final
                """);
        Report result = service.preview(new Request(root.toString(), rules.toString(), "it.TestOrder", Map.of()));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertTrue(file(result, "deploy/Blue_42/values.yaml").after().contains("endpoint: final"));
        assertFalse(file(result, "deploy/Blue_42/values.yaml").after().contains("endpoint: middle"));
    }

    @Test void pinnedNativeDeletePropertyLimitationIsExplicitlyCharacterized() throws Exception {
        Path root = fixture("native-delete-limit", true);
        String before = "anchor: &data\n  x: y\ncopy: *data\napp:\n  legacyEndpoint: old\n";
        write(root, "deploy/Blue_42/values.yaml", before);
        Path rules = write(temporary, "native-delete.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: it.NativeDelete
                displayName: Native delete
                description: Pinned-version limitation.
                recipeList:
                  - org.openrewrite.yaml.DeleteProperty:
                      propertyKey: app.legacyEndpoint
                """);
        Report result = service.preview(new Request(root.toString(), rules.toString(), "it.NativeDelete", Map.of()));
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(0, result.changedFiles(), "Limite documentato di DeleteProperty 8.89.0: per questo caso l'esempio usa DeleteKey.");
        assertEquals(before, file(result, "deploy/Blue_42/values.yaml").after());
    }

    @Test void undefinedAliasBlocksTheWholePreview() throws Exception {
        Path root = fixture("undefined-alias", true);
        write(root, "references.yaml", "copy: *missing\n");
        Map<String, String> before = WorkspaceFiles.inventory(root);
        Report result = service.preview(request(root));
        assertTrue(result.hasErrors());
        assertEquals(Status.ERROR, file(result, "references.yaml").status());
        assertEquals(0, result.changedFiles());
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals(before, WorkspaceFiles.inventory(root));
    }

    @Test void deletingAnAnchorStillUsedByAnAliasIsRejected() throws Exception {
        Path root = fixture("dangling-alias", true);
        String before = "app:\n  legacyEndpoint: &host old-service\ncopy: *host\n";
        write(root, "deploy/Blue_42/values.yaml", before);
        Report result = service.preview(request(root));
        assertTrue(result.hasErrors(), "La rimozione dell'anchor non deve lasciare un alias senza definizione.");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("RECIPE_EXECUTION_ERROR")));
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals(before, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @Test void unsupportedComplexSequenceKeyBlocksApplicationWithoutChangingInputs() throws Exception {
        Path root = fixture("complex-key", true);
        String before = "? [region, zone]\n: valore\napp:\n  endpoint: old-service\n";
        write(root, "deploy/Blue_42/values.yaml", before);
        Report result = service.preview(request(root));
        assertTrue(result.hasErrors(), "Limite del parser OpenRewrite 8.89.0, distinto dalle chiavi numeriche supportate.");
        assertEquals(Status.ERROR, file(result, "deploy/Blue_42/values.yaml").status());
        assertEquals(0, result.changedFiles());
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals(before, Files.readString(root.resolve("deploy/Blue_42/values.yaml")));
    }

    @ParameterizedTest @ValueSource(strings = {"create", "rename"})
    void existingSkippedDestinationCannotBeOverwritten(String operation) throws Exception {
        Path root = fixture("destination-" + operation, true);
        String helm = "name: {{ .Release.Name }}\n";
        write(root, "helm.yaml", helm);
        write(root, "rename.yaml", "keep: original\n");
        String recipe = operation.equals("create") ? """
                  - org.openrewrite.yaml.CreateYamlFile:
                      relativeFileName: helm.yaml
                      fileContents: 'created: true'
                      overwriteExisting: true
                """ : """
                  - org.openrewrite.RenameFile:
                      fileMatcher: rename.yaml
                      fileName: helm.yaml
                """;
        Path rules = write(temporary, "collision.yml", """
                type: specs.openrewrite.org/v1beta/recipe
                name: it.Collision
                displayName: Destination collision
                description: Must preserve an existing skipped file.
                recipeList:
                """ + recipe);
        Map<String, String> before = WorkspaceFiles.inventory(root);
        Report result = service.preview(new Request(root.toString(), rules.toString(), "it.Collision", Map.of()));
        assertTrue(result.hasErrors(), result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("RECIPE_EXECUTION_ERROR") && d.message().contains("destinazione già esistente")));
        assertEquals(Status.SKIPPED, file(result, "helm.yaml").status());
        assertThrows(WorkbenchException.class, () -> service.apply(result));
        assertEquals(before, WorkspaceFiles.inventory(root));
    }
}
