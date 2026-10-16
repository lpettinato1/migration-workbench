package it.migration.workbench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Inventario condiviso da preview e preflight: include anche i file di contesto invariati. */
public final class WorkspaceFiles {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private WorkspaceFiles() { }

    public static Path root(String value) throws IOException {
        if (value == null || value.isBlank()) throw new WorkbenchException("Indicare una cartella da elaborare.");
        Path root = Path.of(value).toRealPath();
        if (!Files.isDirectory(root)) throw new WorkbenchException("La root non è una cartella: " + root);
        return root;
    }

    public static List<Path> yamlFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && (!dir.toRealPath().startsWith(root) || dir.getFileName().toString().equalsIgnoreCase(".git") ||
                        Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yaml") || name.endsWith(".yml")) files.add(path);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(p -> relative(root, p)));
        return files;
    }

    public static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String hashFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            return sha256(("symlink:" + Files.readSymbolicLink(file)).getBytes(StandardCharsets.UTF_8));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1; ) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String inventoryHash(Map<String, String> inventory) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(inventory).forEach((path, hash) -> canonical.append(path).append('\0').append(hash).append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> inventory(Path root) throws IOException {
        Map<String, String> inventory = new TreeMap<>();
        for (Path file : yamlFiles(root)) inventory.put(relative(root, file), hashFile(file));
        return inventory;
    }

    public static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    }

    public static String decode(byte[] bytes) throws CharacterCodingException {
        int offset = hasBom(bytes) ? 3 : 0;
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    public static byte[] encode(String text, boolean bom) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        if (!bom) return content;
        byte[] result = new byte[content.length + 3];
        result[0] = (byte) 0xef; result[1] = (byte) 0xbb; result[2] = (byte) 0xbf;
        System.arraycopy(content, 0, result, 3, content.length);
        return result;
    }

    public static Path safePath(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new WorkbenchException("Percorso vuoto nell'anteprima.");
        Path fragment = Path.of(relative);
        Path result = root.resolve(fragment).normalize();
        if (fragment.isAbsolute() || !result.startsWith(root) || result.equals(root))
            throw new WorkbenchException("Percorso fuori dalla root: " + relative);
        for (Path part : fragment) if (part.toString().equalsIgnoreCase(".git"))
            throw new WorkbenchException("La migrazione non può modificare .git: " + relative);
        for (Path current = result; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new WorkbenchException("Link simbolico non modificabile: " + relative);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().startsWith(root))
                throw new WorkbenchException("Il percorso attraversa un collegamento esterno: " + relative);
        }
        return result;
    }

    public static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".migration-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
