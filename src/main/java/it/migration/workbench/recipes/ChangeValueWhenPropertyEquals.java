package it.migration.workbench.recipes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.*;
import org.openrewrite.yaml.ChangePropertyValue;
import org.openrewrite.yaml.JsonPathMatcher;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

/** Esempio reale di condizione fra file: accumulatore nuovo per ogni esecuzione. */
public final class ChangeValueWhenPropertyEquals extends ScanningRecipe<ChangeValueWhenPropertyEquals.State> {
    @Option(displayName = "File della condizione", description = "Percorso relativo alla root del file da leggere.", example = "config/application.yaml")
    private final String conditionFile;
    @Option(displayName = "Proprietà della condizione", description = "JSONPath della proprietà da confrontare.", example = "$.security.enabled")
    private final String conditionPath;
    @Option(displayName = "Valore atteso", description = "Valore scalare necessario per applicare la modifica.", example = "true")
    private final String expectedValue;
    @Option(displayName = "File da modificare", description = "Glob relativo alla root; non limita la scansione della condizione.", example = "deploy/**/values.yaml")
    private final String targetFiles;
    @Option(displayName = "Proprietà da modificare", description = "Percorso puntato della proprietà di destinazione.", example = "credentials.enabled")
    private final String propertyKey;
    @Option(displayName = "Nuovo valore", description = "Valore da impostare se la condizione è soddisfatta.", example = "true")
    private final String newValue;

    public static final class State { boolean matches; }

    @JsonCreator
    public ChangeValueWhenPropertyEquals(@JsonProperty("conditionFile") String conditionFile,
                                        @JsonProperty("conditionPath") String conditionPath,
                                        @JsonProperty("expectedValue") String expectedValue,
                                        @JsonProperty("targetFiles") String targetFiles,
                                        @JsonProperty("propertyKey") String propertyKey,
                                        @JsonProperty("newValue") String newValue) {
        this.conditionFile = conditionFile; this.conditionPath = conditionPath;
        this.expectedValue = expectedValue; this.targetFiles = targetFiles;
        this.propertyKey = propertyKey; this.newValue = newValue;
    }

    @Override public String getDisplayName() { return "Modifica un valore se una proprietà in un altro file corrisponde"; }
    @Override public String getDescription() { return "Legge la condizione nel repository corrente e modifica solo i file di destinazione quando è vera."; }
    @Override public State getInitialValue(ExecutionContext context) { return new State(); }

    @Override public Validated<Object> validate() {
        return super.validate().and(JsonPathMatcher.validate("conditionPath", conditionPath));
    }

    @Override public TreeVisitor<?, ExecutionContext> getScanner(State state) {
        JsonPathMatcher matcher = new JsonPathMatcher(conditionPath);
        return new YamlIsoVisitor<>() {
            @Override public Yaml.Documents visitDocuments(Yaml.Documents documents, ExecutionContext context) {
                if (!documents.getSourcePath().toString().replace('\\', '/').equals(conditionFile.replace('\\', '/'))) return documents;
                return super.visitDocuments(documents, context);
            }
            @Override public Yaml.Mapping.Entry visitMappingEntry(Yaml.Mapping.Entry entry, ExecutionContext context) {
                if (matcher.matches(getCursor()) && entry.getValue() instanceof Yaml.Scalar scalar && expectedValue.equals(scalar.getValue()))
                    state.matches = true;
                return super.visitMappingEntry(entry, context);
            }
        };
    }

    @Override public TreeVisitor<?, ExecutionContext> getVisitor(State state) {
        return state.matches ? new ChangePropertyValue(propertyKey, newValue, null, false, false, targetFiles).getVisitor() : TreeVisitor.noop();
    }

    public String getConditionFile() { return conditionFile; }
    public String getConditionPath() { return conditionPath; }
    public String getExpectedValue() { return expectedValue; }
    public String getTargetFiles() { return targetFiles; }
    public String getPropertyKey() { return propertyKey; }
    public String getNewValue() { return newValue; }
}

