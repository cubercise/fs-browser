package io.cubercise.fsbrowser.storage;

import io.cubercise.fsbrowser.sandbox.InvalidPathException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The only component allowed to create Entries inside the Root. Callers
 * hand it a directory the {@link io.cubercise.fsbrowser.sandbox.Sandbox}
 * already resolved and the raw client-supplied filename; this class owns
 * the naming rules, so no write path can bypass them:
 *
 * <ul>
 *   <li>The filename must be a plain name — null byte, path separator
 *       ({@code /} or {@code \}), empty, or {@code .}/{@code ..} names are
 *       rejected with {@link InvalidPathException} (→ 400). Path-shaped
 *       names are refused rather than silently basename-ed: a request for
 *       {@code a/b} is a client bug the caller should see, and storing
 *       something other than what was validated would make the 201
 *       response a lie.</li>
 *   <li>An existing Entry is never overwritten: {@link EntryConflictException}
 *       (→ 409), checked up front and re-checked against the copy's own
 *       race window, so two concurrent uploads of one name cannot both
 *       win.</li>
 * </ul>
 */
@Component
public class EntryStore {

    /**
     * Stores one uploaded stream as a new Entry named {@code filename}
     * inside {@code dir} (a Sandbox-resolved directory).
     *
     * @return the stored Entry's path
     * @throws InvalidPathException   bad filename shape (→ 400)
     * @throws EntryConflictException an Entry with that name exists (→ 409)
     */
    public Path store(Path dir, String filename, InputStream content) throws IOException {
        String name = sanitizeFilename(filename);
        Path target = plainTarget(dir, name, filename);
        if (Files.exists(target)) {
            throw new EntryConflictException("An Entry named '" + name + "' already exists");
        }
        try {
            Files.copy(content, target);
        } catch (FileAlreadyExistsException e) {
            // Lost a race with a concurrent upload of the same name.
            throw new EntryConflictException("An Entry named '" + name + "' already exists");
        }
        return target;
    }

    /**
     * Renames one Entry (file or directory — children move with a renamed
     * directory, {@link Files#move} semantics) inside {@code dir}. The new
     * name passes the same shape rules as an uploaded filename; an existing
     * target is never overwritten ({@link EntryConflictException}). The move
     * is attempted {@link StandardCopyOption#ATOMIC_MOVE atomic} first and
     * falls back to a plain move when the filesystem does not support
     * atomic moves (e.g. some network/overlay mounts) — the fallback still
     * refuses to clobber, so the observable contract is unchanged.
     *
     * @return the renamed Entry's path
     * @throws InvalidPathException   bad name shape for {@code from} or {@code to} (→ 400)
     * @throws EntryNotFoundException no Entry named {@code from} in {@code dir} (→ 404)
     * @throws EntryConflictException an Entry named {@code to} already exists (→ 409)
     */
    public Path rename(Path dir, String from, String to) throws IOException {
        String sourceName = sanitizeFilename(from);
        String targetName = sanitizeFilename(to);
        Path source = dir.resolve(sourceName);
        Path target = plainTarget(dir, targetName, to);
        if (!Files.exists(source)) {
            throw new EntryNotFoundException("No Entry named '" + sourceName + "' in this directory");
        }
        if (Files.exists(target)) {
            throw new EntryConflictException("An Entry named '" + targetName + "' already exists");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Documented fallback: same directory, no REPLACE_EXISTING — a
            // plain move is still all-or-nothing on the name and cannot
            // overwrite. (Rethrows FileAlreadyExistsException as conflict below.)
            Files.move(source, target);
        } catch (FileAlreadyExistsException e) {
            // Lost a race with a concurrent create/rename onto the same name.
            throw new EntryConflictException("An Entry named '" + targetName + "' already exists");
        }
        return target;
    }

    /**
     * Deletes one Entry (file or empty directory) inside {@code dir}.
     *
     * <p>v1 scope choice (documented in the README too): deleting a
     * <em>non-empty</em> directory is refused with
     * {@link EntryConflictException} rather than silently walking and
     * removing the tree. Recursive delete is a dangerous default for a
     * browser UI; the user empties the directory first, one visible step
     * at a time. An empty directory deletes normally.
     *
     * @throws InvalidPathException   bad name shape (→ 400)
     * @throws EntryNotFoundException no Entry named {@code name} in {@code dir} (→ 404)
     * @throws EntryConflictException the Entry is a non-empty directory (→ 409)
     */
    public void delete(Path dir, String name) throws IOException {
        String entryName = sanitizeFilename(name);
        Path target = plainTarget(dir, entryName, name);
        if (!Files.exists(target)) {
            throw new EntryNotFoundException("No Entry named '" + entryName + "' in this directory");
        }
        if (Files.isDirectory(target)) {
            try (Stream<Path> children = Files.list(target)) {
                if (children.findAny().isPresent()) {
                    throw new EntryConflictException(
                            "Directory '" + entryName + "' is not empty — empty it first (recursive delete is not supported)");
                }
            }
        }
        Files.delete(target);
    }

    /**
     * Resolves a sanitized name inside {@code dir} and asserts the invariant
     * "an Entry target sits directly in the given directory". The name has
     * already passed every shape rule, so the parent check cannot fire —
     * but it is cheap, and it is the one place that would catch a future
     * edit to {@link #sanitizeFilename} silently allowing pathy names.
     */
    private static Path plainTarget(Path dir, String name, String rawName) {
        Path target = dir.resolve(name).normalize();
        if (!target.getParent().equals(dir)) {
            throw new InvalidPathException("Name must be a plain name, not a path: " + rawName);
        }
        return target;
    }

    /**
     * Normalizes a client-supplied filename to a storable Entry name:
     * surrounding whitespace stripped, then shape-checked. Rejects null,
     * null-byte, empty, dot, dot-dot and separator-containing names.
     */
    static String sanitizeFilename(String filename) {
        if (filename == null) {
            throw new InvalidPathException("Uploaded part has no filename");
        }
        if (filename.indexOf('\0') >= 0) {
            throw new InvalidPathException("Filename contains a null byte");
        }
        String name = filename.strip();
        if (name.isEmpty()) {
            throw new InvalidPathException("Filename is empty");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new InvalidPathException("Filename must be a plain name, not a path: " + filename);
        }
        if (name.equals(".") || name.equals("..")) {
            throw new InvalidPathException("Filename is not a valid Entry name: " + name);
        }
        return name;
    }
}
