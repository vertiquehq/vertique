// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;

/**
 * A public composite {@link ContextHolder.Scope} that closes its constituent scopes in reverse
 * (LIFO) order.
 *
 * <p>This is the public lift of the private inner {@code CompositeScope} inside
 * {@link ContextScopeBinder}, intended for use by framework infrastructure that composes scopes
 * from heterogeneous sources (e.g., {@link InboundExecutionContextScope}). It adds
 * throw-collecting on close: if any constituent scope's {@link ContextHolder.Scope#close()} throws,
 * the remaining scopes are still closed and the first exception is rethrown with any subsequent
 * close-time exceptions attached via {@link Throwable#addSuppressed(Throwable)}.
 *
 * <p>Null elements in the varargs array are silently skipped.
 *
 * <p>If all elements are null or the array is empty, {@link ContextScopes#noop()} is returned
 * directly.
 */
public final class CompositeContextScope {

    private CompositeContextScope() {}

    // --- Factory ---

    /**
     * Composes zero or more {@link ContextHolder.Scope} instances into a single scope that closes
     * them in LIFO order. Null elements in the array are skipped.
     *
     * <p>If the effective set of non-null scopes is empty, returns {@link ContextScopes#noop()}.
     *
     * @param scopes the scopes to compose; may be empty or contain null elements
     * @return a composite scope that closes constituents in LIFO order; never {@code null}
     */
    public static ContextHolder.Scope of(ContextHolder.Scope... scopes) {
        if (scopes == null || scopes.length == 0) {
            return ContextScopes.noop();
        }
        // Count non-null scopes; if none, return noop
        int nonNullCount = 0;
        for (ContextHolder.Scope s : scopes) {
            if (s != null) {
                nonNullCount++;
            }
        }
        if (nonNullCount == 0) {
            return ContextScopes.noop();
        }
        // Compact the array to remove nulls
        ContextHolder.Scope[] compacted = new ContextHolder.Scope[nonNullCount];
        int i = 0;
        for (ContextHolder.Scope s : scopes) {
            if (s != null) {
                compacted[i++] = s;
            }
        }
        return new Impl(compacted);
    }

    // --- Implementation ---

    /**
     * Internal composite scope implementation. Closes scopes in LIFO order with throw-collecting.
     */
    private static final class Impl implements ContextHolder.Scope {

        private final ContextHolder.Scope[] scopes;
        private volatile boolean closed = false;

        Impl(ContextHolder.Scope[] scopes) {
            this.scopes = scopes.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException primary = null;
            for (int i = scopes.length - 1; i >= 0; i--) {
                try {
                    scopes[i].close();
                } catch (RuntimeException ex) {
                    if (primary == null) {
                        primary = ex;
                    } else {
                        primary.addSuppressed(ex);
                    }
                }
            }
            if (primary != null) {
                throw primary;
            }
        }
    }
}
