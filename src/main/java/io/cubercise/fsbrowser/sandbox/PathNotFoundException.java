package io.cubercise.fsbrowser.sandbox;

/**
 * Thrown when a resolved path looked syntactically fine but does not exist on
 * disk (or is not a directory). Mapped to HTTP 404 by the shared handler.
 */
public class PathNotFoundException extends RuntimeException {

    public PathNotFoundException(String message) {
        super(message);
    }
}
