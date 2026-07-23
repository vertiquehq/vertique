// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.config.DefaultHeadersConfig;
import dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware;
import dev.vertique.rest.core.middleware.ContextualLoggingMiddleware;
import dev.vertique.rest.core.middleware.DefaultHeadersMiddleware;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.ext.web.RoutingContext;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the {@link Middleware} ordering contract.
 *
 * <p>Covers priority-based ordering within a phase, scope assignments, and the
 * {@code SYSTEM_FIRST} phase dominance guarantee for {@link RequestContextLifecycle}.
 */
class MiddlewareOrderTest {

    @Test
    @DisplayName("Middlewares should be ordered correctly when sorted")
    void shouldOrderCorrectly() {
        List<Middleware> middlewares = List.of(
                new ContentTypeValidationMiddleware(),
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build()),
                new ContextualLoggingMiddleware(),
                new RequestContextLifecycle());

        List<Middleware> sorted =
                middlewares.stream().sorted(OrderedExtension.comparator()).toList();

        assertInstanceOf(RequestContextLifecycle.class, sorted.get(0));
        assertInstanceOf(ContextualLoggingMiddleware.class, sorted.get(1));
        assertInstanceOf(DefaultHeadersMiddleware.class, sorted.get(2));
        assertInstanceOf(ContentTypeValidationMiddleware.class, sorted.get(3));
    }

    @Test
    @DisplayName("RequestContextLifecycle must sort before ContextualLoggingMiddleware")
    void requestContextLifecycleMustPrecedeContextualLogging() {
        assertTrue(
                new RequestContextLifecycle().priority() < new ContextualLoggingMiddleware().priority(),
                "RequestContextLifecycle (Integer.MIN_VALUE) must have lower priority than "
                        + "ContextualLoggingMiddleware (0) so the lifecycle handle exists when logging runs");
    }

    @Test
    @DisplayName("ROOT-scoped middlewares should come before API-scoped")
    void shouldHaveCorrectScopes() {
        assertEquals(MiddlewareScope.ROOT, new ContextualLoggingMiddleware().scope());
        assertEquals(
                MiddlewareScope.ROOT,
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build()).scope());
        assertEquals(MiddlewareScope.API, new ContentTypeValidationMiddleware().scope());
    }

    @Test
    @DisplayName("SYSTEM_FIRST phase dominates APPLICATION phase regardless of priority value")
    void lifecycleSystemFirstDominatesApplication() {
        // Stub APPLICATION middleware whose priority matches RequestContextLifecycle.ORDER
        // — the numerically lowest possible value — so a priority-only sort would incorrectly
        // tie or interleave them. Phase must win.
        Middleware appMiddleware = new Middleware() {
            @Override
            public int priority() {
                return Integer.MIN_VALUE;
            }

            @Override
            public void handle(RoutingContext event) {}
        };

        RequestContextLifecycle lifecycle = new RequestContextLifecycle();

        // Verify phase assignment
        assertEquals(ExtensionPhase.SYSTEM_FIRST, lifecycle.phase());
        assertEquals(ExtensionPhase.APPLICATION, appMiddleware.phase());

        // Sort using the canonical OrderedExtension comparator
        List<Middleware> sorted = List.of(appMiddleware, lifecycle).stream()
                .sorted(OrderedExtension.comparator())
                .toList();

        // RequestContextLifecycle (SYSTEM_FIRST) must be first even though both share
        // the same priority value
        assertInstanceOf(
                RequestContextLifecycle.class,
                sorted.get(0),
                "SYSTEM_FIRST phase must dominate APPLICATION regardless of equal priority");
        assertSame(
                appMiddleware,
                sorted.get(1),
                "APPLICATION-phase middleware with same priority must sort after SYSTEM_FIRST");
    }

    // --- Legacy comparator compatibility check ---

    @Test
    @DisplayName("Comparator.comparingInt(Middleware::priority) still produces the same intra-phase order")
    void legacyPriorityComparatorProducesSameIntraPhaseOrder() {
        List<Middleware> middlewares = List.of(
                new ContentTypeValidationMiddleware(),
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build()),
                new ContextualLoggingMiddleware());

        List<Middleware> byPriority = middlewares.stream()
                .sorted(Comparator.comparingInt(Middleware::priority))
                .toList();

        assertInstanceOf(ContextualLoggingMiddleware.class, byPriority.get(0));
        assertInstanceOf(DefaultHeadersMiddleware.class, byPriority.get(1));
        assertInstanceOf(ContentTypeValidationMiddleware.class, byPriority.get(2));
    }
}
