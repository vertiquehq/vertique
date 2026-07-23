// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContextScopes}.
 *
 * <p>Verifies that {@link ContextScopes#noop()} returns the singleton no-op scope, that
 * closing it is a no-op (no exception), and that calling close multiple times is safe.
 */
class ContextScopesTest {

    @Test
    @DisplayName("noop() returns a scope whose close() is a no-op (no exception thrown)")
    void noopCloseIsNoOp() {
        ContextHolder.Scope scope = ContextScopes.noop();
        assertDoesNotThrow(scope::close);
    }

    @Test
    @DisplayName("noop() close is idempotent — multiple calls are safe")
    void noopCloseIsIdempotent() {
        ContextHolder.Scope scope = ContextScopes.noop();
        assertDoesNotThrow(() -> {
            scope.close();
            scope.close();
            scope.close();
        });
    }

    @Test
    @DisplayName("noop() returns the same singleton across multiple calls")
    void noopReturnsSameSingleton() {
        ContextHolder.Scope first = ContextScopes.noop();
        ContextHolder.Scope second = ContextScopes.noop();
        assertSame(first, second, "noop() must return the same singleton instance");
    }
}
