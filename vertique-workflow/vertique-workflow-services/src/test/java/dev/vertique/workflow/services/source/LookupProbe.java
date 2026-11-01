// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.InvocationOrigin;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records what a stage function sees when the plan calls it: the Vert.x context it runs on and the
 * context values bound there. A plan's stage calls {@link #sample(String)} under its own name, and
 * the proof reads {@link #seenBy(String)} afterwards.
 */
final class LookupProbe {

    /** What one stage saw. */
    record Seen(
            ContextInternal context,
            SecurityContext security,
            CorrelationContext correlation,
            InvocationOrigin origin,
            ScopeContext scope) {}

    private final DefaultContextHolder holder;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    LookupProbe(DefaultContextHolder holder) {
        this.holder = holder;
    }

    /** Records what the calling stage sees under the given name and returns it. */
    Seen sample(String stage) {
        ContextInternal context = (ContextInternal) Vertx.currentContext();
        Seen now = new Seen(
                context,
                holder.current(SecurityContext.class).orElse(null),
                holder.current(CorrelationContext.class).orElse(null),
                holder.current(InvocationOrigin.class).orElse(null),
                holder.current(ScopeContext.class).orElse(null));
        seen.put(stage, now);
        return now;
    }

    /** What the named stage saw, or null when it did not run. */
    Seen seenBy(String stage) {
        return seen.get(stage);
    }
}
