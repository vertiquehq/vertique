// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.security.SecurityContext;
import java.util.Optional;

/**
 * Static accessor for context values during service dispatch.
 *
 * <p>Thin facade over {@link DefaultContextHolder} — delegates all reads to the core
 * context-propagation substrate's {@link io.vertx.core.spi.context.storage.ContextLocal} slot,
 * which is registered once at Vert.x bootstrap by
 * {@link dev.vertique.context.ContextLocalServiceProvider}. The {@link SecurityContext},
 * when present, is stored under {@code SecurityContext.class.getName()} like any other typed
 * dispatch-context value.
 *
 * <p>Retained as a compatibility entry point for service code that reads context via a static
 * accessor. New code should depend on {@link dev.vertique.core.context.ContextHolder} via Dagger.
 */
public final class DispatchContext {

    private DispatchContext() {}

    /**
     * Returns the current {@link SecurityContext}, or {@code null} if not in a dispatch context,
     * if the caller is unauthenticated, or if no Vert.x context is active.
     *
     * @return the current security context, or {@code null}
     */
    public static SecurityContext currentSecurityContext() {
        return current(SecurityContext.class).orElse(null);
    }

    /**
     * Returns a typed dispatch-context value, or empty if not present or if no dispatch is active.
     *
     * @param type the context value type
     * @param <T>  the context type
     * @return the context value, or empty
     */
    public static <T> Optional<T> current(Class<T> type) {
        return DefaultContextHolder.currentValue(type);
    }
}
