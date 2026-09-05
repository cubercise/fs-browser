package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.root.RootConfiguration;
import java.nio.file.Path;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP seam of the walking skeleton: proves the backend is live and reports
 * which Root the app is browsing.
 */
@RestController
@RequestMapping("/api")
@Import(RootConfiguration.class)
public class PingController {

    private final Path root;

    public PingController(Path root) {
        this.root = root;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("pong", true, "root", root.toString());
    }
}
