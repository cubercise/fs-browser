package io.cubercise.fsbrowser.mode;

import org.springframework.stereotype.Component;

/**
 * The write gate: every mutating handler calls {@link #requireWritable()}
 * as its very first statement — before path resolution, before any body
 * parsing — so in read-only mode write endpoints fail fast with 403 no
 * matter what the rest of the request looks like (a missing target
 * directory still yields 403, not 404: the mode refusal wins).
 *
 * <p>Mechanism choice (one, on purpose): an explicit guard service invoked
 * at the top of each write handler, not an annotation+interceptor. That
 * mirrors this codebase's existing shape, where authority is an injected
 * object called explicitly (the {@link io.cubercise.fsbrowser.sandbox.Sandbox}
 * for paths) and failures translate to HTTP in the one shared advice class;
 * the guard's call site stays visible in the handler and trivially
 * unit-testable, and with a handful of write endpoints an interceptor's
 * indirection would only hide the branch from the reader. Should write
 * endpoints multiply, an annotation fronts the same call — the contract
 * (ReadOnlyException → 403) would not change.
 */
@Component
public class WriteGuard {

    private final Mode mode;

    public WriteGuard(Mode mode) {
        this.mode = mode;
    }

    public void requireWritable() {
        if (mode.readOnly()) {
            throw new ReadOnlyException(
                    "This instance is read-only (FSB_MODE=read-only); write endpoints are disabled");
        }
    }
}
