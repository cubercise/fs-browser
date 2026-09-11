package io.cubercise.fsbrowser.web;

import java.nio.file.Path;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP seam of the walking skeleton: proves the backend is live and reports
 * which Root the app is browsing.
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private final Path root;

    public PingController(Path root) {
        this.root = root;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse(true, root.toString());
    }

    /**
     * Mirrors the frontend's API-client shape so the two sides stay honest.
     */
    public record PingResponse(boolean pong, String root) {
    }
}
