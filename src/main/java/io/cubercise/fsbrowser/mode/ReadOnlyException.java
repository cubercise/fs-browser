package io.cubercise.fsbrowser.mode;

/**
 * Thrown by the {@link WriteGuard} when a write endpoint is hit while the
 * instance runs read-only; the shared exception handler translates it to
 * 403 with an {@code {"error": …}} body.
 */
public class ReadOnlyException extends RuntimeException {

    public ReadOnlyException(String message) {
        super(message);
    }
}
