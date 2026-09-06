package io.cubercise.fsbrowser.sandbox;

/**
 * Thrown when a request path is shaped in a way the Sandbox will never accept
 * (null bytes, traversal, escapes). Mapped to HTTP 400 by the shared handler.
 */
public class InvalidPathException extends RuntimeException {

    public InvalidPathException(String message) {
        super(message);
    }
}
