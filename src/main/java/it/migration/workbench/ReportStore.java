package it.migration.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.migration.workbench.model.Preview.Report;

import java.io.IOException;
import java.nio.file.*;

public final class ReportStore {
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void write(Path path, Report report) throws IOException {
        Path root = WorkspaceFiles.root(report.root());
        Path destination = path.toAbsolutePath().normalize();
        if (destination.startsWith(root))
            throw new WorkbenchException("Salvare il report fuori dalla root da migrare, per non alterare gli input dell'anteprima.");
        Path existingParent = destination.getParent();
        while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) existingParent = existingParent.getParent();
        if (existingParent == null || existingParent.toRealPath().startsWith(root))
            throw new WorkbenchException("La destinazione del report attraversa la root da migrare.");
        Files.createDirectories(destination.getParent());
        if (destination.getParent().toRealPath().startsWith(root))
            throw new WorkbenchException("Salvare il report fuori dalla root da migrare, per non alterare gli input dell'anteprima.");
        WorkspaceFiles.atomicWrite(destination, json.writeValueAsBytes(report));
    }

    public Report read(Path path) throws IOException { return json.readValue(path.toFile(), Report.class); }
}
