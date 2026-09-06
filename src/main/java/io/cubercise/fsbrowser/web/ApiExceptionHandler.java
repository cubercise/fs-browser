package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.sandbox.InvalidPathException;
import io.cubercise.fsbrowser.sandbox.PathNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place translating Sandbox violations into HTTP: bad shape/traversal →
 * 400, missing target → 404. Controllers stay free of status codes.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidPathException.class)
    ResponseEntity<Map<String, String>> invalidPath(InvalidPathException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PathNotFoundException.class)
    ResponseEntity<Map<String, String>> pathNotFound(PathNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
        // Note: messages may contain resolved server paths; acceptable for a
        // read-only local tool, revisit if fs-browser ever faces a network.
    }
}
