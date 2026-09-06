package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.mode.WriteGuard;
import io.cubercise.fsbrowser.sandbox.Sandbox;
import io.cubercise.fsbrowser.storage.EntryStore;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write seam: renames one Entry (file or directory) inside a directory of
 * the Root. Checks run in the fixed order the other write endpoints use,
 * so each failure mode has one status, independent of the others:
 *
 * <ol>
 *   <li>{@link WriteGuard#requireWritable()} — read-only mode answers 403
 *       before any path or body is even looked at (a mode refusal always
 *       wins over 404/400, mirroring upload).</li>
 *   <li>Body shape — missing or blank {@code from}/{@code to} fields are
 *       400, rejected here before path resolution (a malformed request is
 *       the client's bug, not the directory's).</li>
 *   <li>Parent directory — through the {@link Sandbox} like every path:
 *       traversal → 400, missing → 404. There is no second path code path
 *       for writes.</li>
 *   <li>Names and conflict — {@link EntryStore} rules, the same ones an
 *       upload passes: pathy/empty/dot/null-byte names → 400, a missing
 *       source name → 404, an existing target name → 409 (never an
 *       overwrite).</li>
 * </ol>
 *
 * <p>Success answers 200 with the renamed Entry's {@code name}. The body
 * contract is exactly {@code {"path": …, "from": …, "to": …}} (JSON), kept
 * testable and stable; directories rename whole (children move with them).
 */
@RestController
@RequestMapping("/api")
public class RenameController {

    private final WriteGuard writeGuard;
    private final Sandbox sandbox;
    private final EntryStore entryStore;

    public RenameController(WriteGuard writeGuard, Sandbox sandbox, EntryStore entryStore) {
        this.writeGuard = writeGuard;
        this.sandbox = sandbox;
        this.entryStore = entryStore;
    }

    /** Mirrors the frontend's API-client shape so the two sides stay honest. */
    public record RenameRequest(String path, String from, String to) {
    }

    @PostMapping("/rename")
    public ResponseEntity<RenamedEntry> rename(@RequestBody(required = false) RenameRequest request) throws IOException {
        writeGuard.requireWritable();
        if (request == null
                || isBlank(request.from())
                || isBlank(request.to())) {
            throw new BlankMutationException("Expected a JSON body with non-blank 'from' and 'to' names");
        }
        Path dir = sandbox.resolveDir(request.path());
        Path renamed = entryStore.rename(dir, request.from(), request.to());
        return ResponseEntity.ok(new RenamedEntry(renamed.getFileName().toString()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Mirrors the frontend's API-client shape so the two sides stay honest. */
    public record RenamedEntry(String name) {
    }
}
