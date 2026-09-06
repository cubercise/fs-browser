package io.cubercise.fsbrowser.web;

import io.cubercise.fsbrowser.mode.Mode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mode seam: lets the SPA discover once, at load, whether write
 * affordances may exist at all — in read-only mode the UI renders no
 * upload control (absent, not disabled), so the mode is part of the
 * layout, not a per-action check.
 */
@RestController
@RequestMapping("/api")
public class ModeController {

    private final Mode mode;

    public ModeController(Mode mode) {
        this.mode = mode;
    }

    @GetMapping("/mode")
    public ModeResponse mode() {
        return new ModeResponse(mode.wireName());
    }

    /** Mirrors the frontend's API-client shape so the two sides stay honest. */
    public record ModeResponse(String mode) {
    }
}
