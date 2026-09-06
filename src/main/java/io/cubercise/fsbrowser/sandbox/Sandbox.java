package io.cubercise.fsbrowser.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * The Sandbox: the single place that turns a client-supplied relative path
 * into a Path the rest of the app is allowed to touch.
 *
 * <p>Contract (checked in order, before any caller I/O):
 * <ol>
 *   <li>Null bytes anywhere in the request reject it outright ({@link InvalidPathException}).
 *   <li>The empty path resolves to the Root itself.
 *   <li>The path is resolved against the Root (so absolute-looking input is
 *       rebased, not honoured) and must stay inside the Root, or the request
 *       is rejected ({@link InvalidPathException}).
 *   <li>{@link Files#realPath} (which follows symlinks) must still land inside
 *       the Root's real path — this is what defeats symlink escapes.
 *   <li>The real path must exist; {@link #resolveDir(String)} then requires a
 *       directory and {@link #resolveFile(String)} a regular file, else
 *       {@link PathNotFoundException}.
 * </ol>
 *
 * <p>Every endpoint (tree listing, file download/preview, later mutations)
 * reuses this service; keep its API narrow on purpose.
 */
@Service
public class Sandbox {

    private final Path root;
    private final Path realRoot;

    public Sandbox(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.realRoot = toRealPathUnchecked(this.root);
    }

    /**
     * Resolves a client-supplied directory path against the Root, enforcing
     * containment at both the lexical and the real (symlink-aware) level.
     *
     * @param requestPath relative path as sent by the client; empty or null means the Root
     * @return the real, canonical directory Path to list
     * @throws InvalidPathException bad shape or traversal attempt (→ 400)
     * @throws PathNotFoundException nothing (readable) there, or not a directory (→ 404)
     */
    public Path resolveDir(String requestPath) {
        Path real = resolveExistingContainedPath(requestPath);
        if (!Files.isDirectory(real)) {
            throw new PathNotFoundException("No such directory inside the Root: " + real);
        }
        return real;
    }

    /**
     * Same containment chain as {@link #resolveDir(String)}, but the resolved
     * path must be a regular file (symlinks to files inside the Root are
     * fine, as they are for directories).
     *
     * @param requestPath relative path as sent by the client
     * @return the real, canonical file Path to serve
     * @throws InvalidPathException bad shape or traversal attempt (→ 400)
     * @throws PathNotFoundException nothing there, or not a regular file (→ 404)
     */
    public Path resolveFile(String requestPath) {
        Path real = resolveExistingContainedPath(requestPath);
        if (!Files.isRegularFile(real)) {
            throw new PathNotFoundException("No such file inside the Root: " + real);
        }
        return real;
    }

    /**
     * The one containment chain both public resolvers share: null-byte
     * rejection, Root-relative resolution (absolute input is rebased), lexical
     * containment, then symlink-aware real-path containment. The caller adds
     * the entry-type requirement.
     */
    private Path resolveExistingContainedPath(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            return realRoot;
        }
        if (requestPath.indexOf('\0') >= 0) {
            throw new InvalidPathException("Path contains a null byte");
        }
        // Resolve against the Root: an absolute requestPath is rebased (its
        // leading component is dropped), and ".." segments are normalized so
        // lexical containment can be checked before touching the filesystem.
        Path candidate = root.resolve(requestPath).normalize();
        if (!isInside(candidate)) {
            throw new InvalidPathException("Path escapes the Root: " + requestPath);
        }
        Path real = toRealPathUnchecked(candidate);
        if (!isInsideReal(real)) {
            throw new InvalidPathException("Path resolves outside the Root (symlink?): " + requestPath);
        }
        return real;
    }

    private boolean isInside(Path p) {
        return p.equals(root) || p.startsWith(root);
    }

    private boolean isInsideReal(Path p) {
        return p.equals(realRoot) || p.startsWith(realRoot);
    }

    private static Path toRealPathUnchecked(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            // Missing link in the chain (NoSuchFileException et al.): not an
            // escape, just absent — surface as 404, never as 500.
            throw new PathNotFoundException("No such directory inside the Root: " + p);
        }
    }
}
