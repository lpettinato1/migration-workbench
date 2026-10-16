package it.migration.workbench;

/** Errore operativo da mostrare senza uno stack trace nella UI o nella CLI. */
public final class WorkbenchException extends RuntimeException {
    public WorkbenchException(String message) { super(message); }
    public WorkbenchException(String message, Throwable cause) { super(message, cause); }
}

