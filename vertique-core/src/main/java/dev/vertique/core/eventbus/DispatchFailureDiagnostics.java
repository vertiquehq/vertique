// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.core.eventbus;

import dev.vertique.core.context.ContextValue;

/** INTERNAL dispatch-context marker requesting value-free failure diagnostics. */
public final class DispatchFailureDiagnostics implements ContextValue {
    /** Shared immutable marker for redacted diagnostics. */
    public static final DispatchFailureDiagnostics REDACT_FATAL = new DispatchFailureDiagnostics();

    private DispatchFailureDiagnostics() {}
}
