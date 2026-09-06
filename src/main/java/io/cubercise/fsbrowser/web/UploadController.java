package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.mode.WriteGuard;
import io.cubercise.fsbrowser.sandbox.Sandbox;
import io.cubercise.fsbrowser.storage.EntryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Write seam: stores one uploaded file as a new Entry of a directory of
 * the Root. Checks run in a fixed order so each failure mode has one
 * status, independent of the others:
 *
 * <ol>
 *   <li>{@link WriteGuard#requireWritable()} — read-only mode answers 403
 *       before any path or body is even looked at (a mode refusal always
 *       wins over 404/400).</li>
 *   <li>Part shape — anything but multipart/form-data with a {@code file}
 *       part is 400 ({@link MissingServletRequestPartException}).</li>
 *   <li>Target directory — through the {@link Sandbox} like every path:
 *       traversal → 400, missing → 404. There is no second path code path
 *       for writes.</li>
 *   <li>Name and conflict — {@link io.cubercise.fsbrowser.storage.EntryStore}
 *       rules: pathy/empty/null-byte names → 400, an existing name → 409.</li>
 * </ol>
 *
 * <p>Success answers 201 with the stored Entry's {@code name} and
 * {@code size}. The upload ceiling (multipart limits) is set in
 * application.properties and enforced by the servlet stack (→ 413).
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private final WriteGuard writeGuard;
    private final Sandbox sandbox;
    private final EntryStore entryStore;

    public UploadController(WriteGuard writeGuard, Sandbox sandbox, EntryStore entryStore) {
        this.writeGuard = writeGuard;
        this.sandbox = sandbox;
        this.entryStore = entryStore;
    }

    @PostMapping("/upload")
    public ResponseEntity<StoredEntry> upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException, MissingServletRequestPartException {

        writeGuard.requireWritable();
        if (file == null) {
            // Covers both a non-multipart request and a multipart one
            // without a 'file' part: either way there is nothing to store.
            throw new MissingServletRequestPartException("file");
        }
        Path dir = sandbox.resolveDir(path);
        Path stored = entryStore.store(dir, file.getOriginalFilename(), file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new StoredEntry(stored.getFileName().toString(), Files.size(stored)));
    }

    /** Mirrors the frontend's API-client shape so the two sides stay honest. */
    public record StoredEntry(String name, long size) {
    }
}
