package io.cubercise.fsbrowser.storage;

/**
 * Thrown when a write would replace an existing Entry (uploading a name
 * that already exists in the target directory); the shared exception
 * handler translates it to 409 with an {@code {"error": …}} body. Refusing
 * to overwrite is the upload contract — the browser shows what would be
 * clobbered, never the server.
 */
public class EntryConflictException extends RuntimeException {

    public EntryConflictException(String message) {
        super(message);
    }
}
