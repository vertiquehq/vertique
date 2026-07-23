// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ContextHolderResolver}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link ContextHolderResolver#priority()} returns {@code 120}.
 *   <li>{@link ContextHolderResolver#resolve(Class, RoutingContext)} returns empty when no value is
 *       bound in the current Vert.x context (including when called outside any Vert.x context).
 *   <li>Returns the bound value when a {@link ContextValue} subtype is bound in a duplicated
 *       Vert.x context.
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextHolderResolverTest {

    /** Minimal {@link ContextValue} fixture for bind/read tests. */
    record TestCtxValue(String name) implements ContextValue {}

    private final ContextHolderResolver resolver = new ContextHolderResolver();

    // --- priority ---

    @Nested
    @DisplayName("priority")
    class Priority {

        @Test
        @DisplayName("priority() returns 120")
        void priorityIsOneTwenty() {
            assertEquals(120, resolver.priority());
        }
    }

    // --- resolve: no binding ---

    @Nested
    @DisplayName("resolve: no binding")
    class NoBinding {

        @Test
        @DisplayName("resolve returns empty outside any Vert.x context (lenient read)")
        void returnsEmptyOutsideVertxContext() {
            RoutingContext ctx = mock(RoutingContext.class);
            // Called outside a Vert.x event-loop thread — ContextValues.current is lenient
            Optional<TestCtxValue> result = resolver.resolve(TestCtxValue.class, ctx);
            assertFalse(result.isPresent());
        }
    }

    // --- resolve: with binding on duplicated context ---

    @Nested
    @DisplayName("resolve: with binding")
    class WithBinding {

        @Test
        @DisplayName("resolve returns the bound value on a duplicated Vert.x context")
        void returnsBoundValueOnDuplicatedContext(Vertx vertx, VertxTestContext testCtx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    TestCtxValue value = new TestCtxValue("hello");
                    try (ContextHolder.Scope scope = ContextValues.bind(TestCtxValue.class, value)) {
                        Optional<TestCtxValue> result =
                                resolver.resolve(TestCtxValue.class, mock(RoutingContext.class));
                        assertTrue(result.isPresent());
                        assertEquals(value, result.get());
                    }
                    testCtx.completeNow();
                } catch (Throwable t) {
                    testCtx.failNow(t);
                }
            });
        }

        @Test
        @DisplayName("resolve returns empty after scope is closed")
        void returnsEmptyAfterScopeClosed(Vertx vertx, VertxTestContext testCtx) {
            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try {
                    TestCtxValue value = new TestCtxValue("temporary");
                    ContextHolder.Scope scope = ContextValues.bind(TestCtxValue.class, value);
                    scope.close();

                    Optional<TestCtxValue> result = resolver.resolve(TestCtxValue.class, mock(RoutingContext.class));
                    assertFalse(result.isPresent());
                    testCtx.completeNow();
                } catch (Throwable t) {
                    testCtx.failNow(t);
                }
            });
        }
    }
}
