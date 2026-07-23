// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoutingContextResolver}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link RoutingContextResolver#priority()} returns {@code 100}.
 *   <li>{@link RoutingContextResolver#resolve(Class, RoutingContext)} returns the routing context
 *       when the requested type is {@link RoutingContext} (or a subtype).
 *   <li>Returns empty when the requested type is not assignable from {@link RoutingContext}.
 *   <li>Returns empty when {@code ctx} is {@code null}.
 * </ul>
 */
class RoutingContextResolverTest {

    private final RoutingContextResolver resolver = new RoutingContextResolver();

    // --- priority ---

    @Nested
    @DisplayName("priority")
    class Priority {

        @Test
        @DisplayName("priority() returns 100")
        void priorityIsOneHundred() {
            assertEquals(100, resolver.priority());
        }
    }

    // --- resolve ---

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("resolve(RoutingContext.class, ctx) returns the ctx instance")
        void resolvesRoutingContext() {
            RoutingContext ctx = mock(RoutingContext.class);
            Optional<RoutingContext> result = resolver.resolve(RoutingContext.class, ctx);

            assertTrue(result.isPresent());
            assertEquals(ctx, result.get());
        }

        @Test
        @DisplayName("resolve(String.class, ctx) returns empty — wrong type")
        void returnsEmptyForWrongType() {
            RoutingContext ctx = mock(RoutingContext.class);
            Optional<String> result = resolver.resolve(String.class, ctx);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("resolve(RoutingContext.class, null) returns empty — null ctx")
        void returnsEmptyForNullContext() {
            Optional<RoutingContext> result = resolver.resolve(RoutingContext.class, null);

            assertFalse(result.isPresent());
        }
    }
}
