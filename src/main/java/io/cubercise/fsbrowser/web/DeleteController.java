package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.mode.WriteGuard;
import io.cubercise.fsbrowser.sandbox.Sandbox;
import io.cubercise.fsbrowser.storage.EntryStore;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write seam: deletes one Entry (file or empty directory) from a directory
 * of the Root. Checks run in the fixed order the other write endpoints
 * use, so each failure mode has one status, independent of the others:
 *
 * <ol>
 *   <li>{@link WriteGuard#requireWritable()} — read-only mode answers 403
 *       before any parameter is even looked at (a mode refusal always wins
 *       over 404/400, mirroring upload).</li>
 *   <li>Parameter shape — a missing or blank {@code name} is 400, rejected
 *       here before path resolution.</li>
 *   <li>Parent directory — through the {@link Sandbox} like every path:
 *       traversal → 400, missing → 404. There is no second path code path
 *       for writes.</li>
 *   <li>Name and state — {@link EntryStore} rules, the same ones an upload
 *       passes: pathy/dot/null-byte names → 400, a missing Entry → 404, a
 *       non-empty directory → 409 (v1 refuses recursive deletes; empty
 *       directories delete fine — documented in EntryStore.delete).</li>
 * </ol>
 *
 * <p>Success answers 204 with no body. The query-param contract is exactly
 * {@code DELETE /api/file?path=<dir>&name=<entry>}, kept testable and
 * stable — the same resource the GET serves, minus its Entry.
 */
@RestController
@RequestMapping("/api")
public class DeleteController {

    private final WriteGuard writeGuard;
    private final Sandbox sandbox;
    private final EntryStore entryStore;

    public DeleteController(WriteGuard writeGuard, Sandbox sandbox, EntryStore entryStore) {
        this.writeGuard = writeGuard;
        this.sandbox = sandbox;
        this.entryStore = entryStore;
    }

    @DeleteMapping("/file")
    public ResponseEntity<Void> delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "name", required = false) String name) throws IOException {

        writeGuard.requireWritable();
        if (name == null || name.isBlank()) {
            throw new BlankMutationException("Expected a non-blank 'name' query parameter");
        }
        Path dir = sandbox.resolveDir(path);
        entryStore.delete(dir, name);
        return ResponseEntity.noContent().build();
    }
}
