package io.cubercise.fsbrowser.web;

/**
 * Thrown when a write request's own shape is unusable — missing or blank
 * rename names, a blank delete name — before any path resolution or
 * filesystem work happens. The shared exception handler translates it to
 * 400 with an {@code {"error": …}} body. Kept in the web package because
 * it describes the HTTP request's shape, not the Entry's.
 */
public class BlankMutationException extends RuntimeException {

    public BlankMutationException(String message) {
        super(message);
    }
}
