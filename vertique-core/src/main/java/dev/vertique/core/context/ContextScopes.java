// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * Factory for shared {@link ContextHolder.Scope} constants.
 *
 * <p>Provides a public singleton no-op scope suitable for use by {@link InboundContextInitializer}
 * implementations that have nothing to bind, and as a sentinel return from
 * {@link CompositeContextScope#of(ContextHolder.Scope...)} when all elements are null or the
 * varargs array is empty.
 */
public final class ContextScopes {

    private ContextScopes() {}

    // --- No-op scope ---

    /**
     * Returns the singleton no-op {@link ContextHolder.Scope}. Calling {@link ContextHolder.Scope#close()}
     * on the returned scope is always a no-op; the same instance is returned on every call.
     *
     * @return the singleton no-op scope; never {@code null}
     */
    public static ContextHolder.Scope noop() {
        return NoOpScope.INSTANCE;
    }

    /** Enum-singleton no-op scope implementation. */
    private enum NoOpScope implements ContextHolder.Scope {
        /** The singleton no-op scope instance. */
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }
}
