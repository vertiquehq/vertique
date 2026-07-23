// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompositeContextScope}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>An empty array or all-null array returns the noop scope.
 *   <li>Multiple scopes close in LIFO order.
 *   <li>Close is idempotent.
 *   <li>Throw-collecting: if a middle scope throws, remaining scopes still close and the first
 *       exception is rethrown with subsequent close exceptions attached as suppressed.
 * </ul>
 */
class CompositeContextScopeTest {

    // --- Empty / all-null inputs ---

    @Test
    @DisplayName("empty array returns ContextScopes.noop()")
    void emptyArrayReturnsNoop() {
        ContextHolder.Scope scope = CompositeContextScope.of();
        assertSame(ContextScopes.noop(), scope, "empty varargs must return ContextScopes.noop()");
    }

    @Test
    @DisplayName("all-null array returns ContextScopes.noop()")
    void allNullArrayReturnsNoop() {
        ContextHolder.Scope scope = CompositeContextScope.of((ContextHolder.Scope) null, null, null);
        assertSame(ContextScopes.noop(), scope, "all-null elements must return ContextScopes.noop()");
    }

    @Test
    @DisplayName("all-null array close is harmless")
    void allNullArrayCloseIsHarmless() {
        ContextHolder.Scope scope = CompositeContextScope.of((ContextHolder.Scope) null, null);
        assertDoesNotThrow(scope::close);
    }

    // --- LIFO close order ---

    @Test
    @DisplayName("multiple scopes close in LIFO order")
    void scopesCloseInLifoOrder() {
        List<Integer> closedOrder = new ArrayList<>();
        ContextHolder.Scope s0 = () -> closedOrder.add(0);
        ContextHolder.Scope s1 = () -> closedOrder.add(1);
        ContextHolder.Scope s2 = () -> closedOrder.add(2);

        ContextHolder.Scope composite = CompositeContextScope.of(s0, s1, s2);
        composite.close();

        assertEquals(List.of(2, 1, 0), closedOrder, "scopes must close in reverse (LIFO) order");
    }

    @Test
    @DisplayName("null elements are skipped during LIFO close")
    void nullElementsSkippedInLifoClose() {
        List<Integer> closedOrder = new ArrayList<>();
        ContextHolder.Scope s0 = () -> closedOrder.add(0);
        ContextHolder.Scope s2 = () -> closedOrder.add(2);

        // s1 is null — should be skipped without NPE
        ContextHolder.Scope composite = CompositeContextScope.of(s0, null, s2);
        assertDoesNotThrow(composite::close);
        assertEquals(List.of(2, 0), closedOrder, "null elements must be skipped in LIFO close");
    }

    // --- Idempotent close ---

    @Test
    @DisplayName("close is idempotent — second close does nothing")
    void closeIsIdempotent() {
        List<Integer> closedOrder = new ArrayList<>();
        ContextHolder.Scope s0 = () -> closedOrder.add(0);
        ContextHolder.Scope s1 = () -> closedOrder.add(1);

        ContextHolder.Scope composite = CompositeContextScope.of(s0, s1);
        composite.close();
        composite.close(); // second close must be a no-op

        assertEquals(List.of(1, 0), closedOrder, "second close must not re-close constituent scopes");
    }

    // --- Throw-collecting ---

    @Test
    @DisplayName("if a scope throws, remaining scopes still close and exception is rethrown")
    void throwingMidwayStillClosesRemainingScopes() {
        List<Integer> closedOrder = new ArrayList<>();
        ContextHolder.Scope s0 = () -> closedOrder.add(0);
        ContextHolder.Scope s1 = () -> {
            throw new RuntimeException("scope-1 close failure");
        };
        ContextHolder.Scope s2 = () -> closedOrder.add(2);

        // Scopes close LIFO: s2 closes first (fine), s1 throws, s0 must still close
        ContextHolder.Scope composite = CompositeContextScope.of(s0, s1, s2);
        RuntimeException thrown = assertThrows(RuntimeException.class, composite::close);

        assertEquals("scope-1 close failure", thrown.getMessage());
        // s2 closed first (LIFO), then s1 threw, then s0 must have been closed
        assertTrue(closedOrder.contains(0), "s0 must still close despite s1 throwing");
        assertTrue(closedOrder.contains(2), "s2 must close before s1 (LIFO)");
    }

    @Test
    @DisplayName("if multiple scopes throw, subsequent exceptions are attached as suppressed")
    void multipleThrowsAttachedAsSuppressed() {
        ContextHolder.Scope s0 = () -> {
            throw new RuntimeException("scope-0 close failure");
        };
        ContextHolder.Scope s1 = () -> {
            throw new RuntimeException("scope-1 close failure");
        };

        // LIFO: s1 closes first and throws, then s0 closes and also throws
        ContextHolder.Scope composite = CompositeContextScope.of(s0, s1);
        RuntimeException thrown = assertThrows(RuntimeException.class, composite::close);

        // First thrown exception (s1, closes first in LIFO) is the primary
        assertEquals("scope-1 close failure", thrown.getMessage());
        // s0's exception is attached as suppressed
        assertEquals(1, thrown.getSuppressed().length, "s0 exception must be attached as suppressed");
        assertEquals("scope-0 close failure", thrown.getSuppressed()[0].getMessage());
    }
}
