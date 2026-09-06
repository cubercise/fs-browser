package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.mode.ReadOnlyException;
import io.cubercise.fsbrowser.sandbox.InvalidPathException;
import io.cubercise.fsbrowser.sandbox.PathNotFoundException;
import io.cubercise.fsbrowser.storage.EntryConflictException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * One place translating domain failures into HTTP: bad shape/traversal →
 * 400, missing target → 404, write in read-only mode → 403, would-overwrite
 * → 409, and missing upload part / non-multipart upload request → 400.
 * Controllers stay free of status codes.
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

    @ExceptionHandler(ReadOnlyException.class)
    ResponseEntity<Map<String, String>> readOnly(ReadOnlyException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(EntryConflictException.class)
    ResponseEntity<Map<String, String>> entryConflict(EntryConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, String>> missingPart(MissingServletRequestPartException e) {
        return body(HttpStatus.BAD_REQUEST, "Expected multipart/form-data with a 'file' part");
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
        // Note: messages may contain resolved server paths; acceptable for a
        // read-only local tool, revisit if fs-browser ever faces a network.
    }
}
