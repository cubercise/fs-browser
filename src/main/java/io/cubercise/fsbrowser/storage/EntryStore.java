package io.cubercise.fsbrowser.storage;

import io.cubercise.fsbrowser.sandbox.InvalidPathException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path target = dir.resolve(name).normalize();
        // Belt and braces: the name passed every shape rule, so this cannot
        // fire — but the invariant "a stored Entry sits directly in the
        // given directory" is cheap to assert at the one write site.
        if (!target.getParent().equals(dir)) {
            throw new InvalidPathException("Filename must be a plain name, not a path: " + filename);
        }
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
