package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.sandbox.Sandbox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browsing seam: lists one directory of the Root per request. All path
 * acceptance logic lives in the {@link Sandbox}; this controller only shapes
 * the response.
 */
@RestController
@RequestMapping("/api")
public class TreeController {

    /** Dirs first, then files; alphabetical (case-insensitive) within groups. */
    private static final Comparator<EntryResponse> ORDER =
            Comparator.comparing((EntryResponse e) -> e.kind() == Kind.DIR ? 0 : 1)
                    .thenComparing(EntryResponse::name, Collator.getInstance(Locale.ROOT));

    private final Sandbox sandbox;

    public TreeController(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @GetMapping("/tree")
    public TreeResponse tree(@RequestParam(value = "path", required = false) String path) {
        Path dir = sandbox.resolveDir(path);
        List<EntryResponse> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.map(TreeController::toEntry)
                    .sorted(ORDER)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory: " + dir, e);
        }
        return new TreeResponse(dir.toString(), entries);
    }

    private static EntryResponse toEntry(Path p) {
        // Size choice: 0 for directories. A dir's Files.size is platform
        // noise (inode bookkeeping), meaningless to a browsing UI.
        boolean isDir = Files.isDirectory(p);
        long size = isDir ? 0L : uncheckedSize(p);
        FileTime modified = uncheckedLastModified(p);
        return new EntryResponse(
                p.getFileName().toString(),
                isDir ? Kind.DIR : Kind.FILE,
                size,
                modified == null ? 0L : modified.toMillis());
    }

    private static long uncheckedSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L; // raced deletion &c. — degrade to 0 rather than 500
        }
    }

    private static FileTime uncheckedLastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return null;
        }
    }

    public enum Kind {
        DIR, FILE
    }

    /** Mirrors the frontend's API-client shape so the two sides stay honest. */
    public record EntryResponse(String name, Kind kind, long size, long lastModified) {
    }

    public record TreeResponse(String path, List<EntryResponse> entries) {
    }
}
