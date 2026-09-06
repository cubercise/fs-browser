package io.cubercise.fsbrowser.storage;

/**
 * Thrown when a mutation names an Entry that does not exist in the
 * Sandbox-resolved directory (renaming or deleting a missing name); the
 * shared exception handler translates it to 404 with an
 * {@code {"error": …}} body. Kept in storage, next to the other Entry
 * lifecycle failures, because only the EntryStore knows what "inside this
 * directory" means.
 */
public class EntryNotFoundException extends RuntimeException {

    public EntryNotFoundException(String message) {
        super(message);
    }
}
