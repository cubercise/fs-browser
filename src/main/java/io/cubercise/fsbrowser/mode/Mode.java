package io.cubercise.fsbrowser.mode;

/**
 * The one deployment-wide permission switch: whether fs-browser may change
 * the files it browses. Read-only is the default because a browser of a
 * directory has no business mutating it until an operator explicitly opts
 * in ({@code FSB_MODE=read-write}); the container image ships that default.
 *
 * <p>A dedicated type rather than a bare string (or a boolean "writable")
 * wherever it flows: the set of modes is closed, the config/wire spellings
 * live in exactly one place, and an invalid FSB_MODE fails at startup
 * instead of silently meaning "read-only".
 */
public enum Mode {
    READ_ONLY("read-only"),
    READ_WRITE("read-write");

    private final String wireName;

    Mode(String wireName) {
        this.wireName = wireName;
    }

    /** The spelling used in config (FSB_MODE) and on the wire (/api/mode). */
    public String wireName() {
        return wireName;
    }

    public boolean readOnly() {
        return this == READ_ONLY;
    }

    /**
     * Parses FSB_MODE's value: null or blank means read-only (the variable
     * is unset); anything but the two exact wire spellings is a deployment
     * error and fails startup with a message naming the fix — a typo must
     * never degrade into an accidentally writable instance.
     */
    public static Mode fromConfig(String configured) {
        if (configured == null || configured.isBlank()) {
            return READ_ONLY;
        }
        for (Mode mode : values()) {
            if (mode.wireName.equals(configured)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "FSB_MODE must be 'read-only' or 'read-write', but was: '" + configured + "'");
    }
}
