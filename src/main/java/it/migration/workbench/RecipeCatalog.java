package it.migration.workbench;

import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SnakeYAML è usato solo per la configurazione delle ricette, non per riscrivere i target. */
public final class RecipeCatalog {
    private static final Pattern PARAMETER = Pattern.compile("\\$\\{(migration\\.[A-Za-z0-9_.-]+)(?::([^}]*))?}");

    public record Loaded(Recipe recipe, String yaml, String sha256) { }

    public Loaded load(Path path, String name, Map<String, String> parameters) throws IOException {
        if (name == null || name.isBlank()) throw new WorkbenchException("Specificare il nome completo della ricetta.");
        if (Files.size(path) > WorkspaceFiles.MAX_FILE_BYTES) throw new WorkbenchException("File delle ricette troppo grande: " + path);
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        loaderOptions.setCodePointLimit(WorkspaceFiles.MAX_FILE_BYTES);
        loaderOptions.setMaxAliasesForCollections(500);
        loaderOptions.setNestingDepthLimit(200);
        Yaml reader = new Yaml(new SafeConstructor(loaderOptions));
        List<Object> documents = new ArrayList<>();
        try {
            for (Object document : reader.loadAll(Files.readString(path, StandardCharsets.UTF_8))) {
                if (!(document instanceof Map<?, ?>)) throw new WorkbenchException("Ogni documento delle ricette deve essere una mappa YAML.");
                documents.add(resolve(document, parameters, new IdentityHashMap<>()));
            }
        } catch (WorkbenchException e) { throw e; }
        catch (RuntimeException e) { throw new WorkbenchException("Configurazione ricette YAML non valida: " + e.getMessage(), e); }
        if (documents.isEmpty()) throw new WorkbenchException("Il file delle ricette è vuoto: " + path);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setSplitLines(false);
        String resolved = new Yaml(dumperOptions).dumpAll(documents.iterator());
        try (var input = new ByteArrayInputStream(resolved.getBytes(StandardCharsets.UTF_8))) {
            var environment = Environment.builder().load(new YamlResourceLoader(input, path.toUri(), new Properties(), getClass().getClassLoader())).build();
            Recipe recipe = environment.activateRecipes(name);
            List<String> failures = recipe.validateAll().stream().filter(v -> !v.isValid())
                    .flatMap(v -> v.failures().stream()).map(v -> v.getProperty() + ": " + v.getMessage()).toList();
            if (!failures.isEmpty()) throw new WorkbenchException("Ricetta non valida: " + String.join("; ", failures));
            return new Loaded(recipe, resolved, WorkspaceFiles.sha256((name + "\n" + resolved).getBytes(StandardCharsets.UTF_8)));
        } catch (WorkbenchException e) { throw e; }
        catch (RuntimeException e) { throw new WorkbenchException("Impossibile caricare la ricetta '" + name + "': " + e.getMessage(), e); }
    }

    private Object resolve(Object value, Map<String, String> parameters, IdentityHashMap<Object, Boolean> ancestors) {
        if (value instanceof String text) {
            Matcher matcher = PARAMETER.matcher(text);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                String replacement = parameters.getOrDefault(matcher.group(1), matcher.group(2));
                if (replacement == null) throw new WorkbenchException("Parametro mancante: " + matcher.group(1));
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }
        if (value instanceof Map<?, ?> map) {
            if (ancestors.put(value, true) != null) throw new WorkbenchException("Riferimento circolare nella configurazione delle ricette.");
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new WorkbenchException("Le opzioni delle ricette devono avere nomi testuali.");
                resolved.put(key, resolve(entry.getValue(), parameters, ancestors));
            }
            ancestors.remove(value);
            return resolved;
        }
        if (value instanceof List<?> list) {
            if (ancestors.put(value, true) != null) throw new WorkbenchException("Riferimento circolare nella configurazione delle ricette.");
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) resolved.add(resolve(item, parameters, ancestors));
            ancestors.remove(value);
            return resolved;
        }
        return value;
    }
}

