// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ContextValues}.
 *
 * <p>Verifies the lenient read / fail-fast write contract: reads ({@code current}, {@code
 * snapshot}) return empty results outside a Vert.x context, while writes ({@code bind},
 * {@code mutate}, {@code mutateIfPresent}, {@code remove}, {@code bindSnapshot} with a non-empty
 * snapshot) throw {@link IllegalStateException} unless called on a duplicated Vert.x context.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextValuesTest {

    // --- lenient reads outside any Vert.x context ---

    @Test
    @DisplayName("current outside any Vert.x context returns Optional.empty")
    void currentOutsideContextReturnsEmpty() {
        assertFalse(ContextValues.current(String.class).isPresent());
    }

    // --- write guard on non-duplicated context ---

    @Test
    @DisplayName("bind on a non-duplicated Vert.x context throws IllegalStateException")
    void bindOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                Context current = Vertx.currentContext();
                assertNotNull(current);
                assertFalse(
                        ((ContextInternal) current).isDuplicate(),
                        "runOnContext on the root context must not be duplicated");
                IllegalStateException ex = assertThrows(
                        IllegalStateException.class, () -> ContextValues.bind(StringCtx.class, new StringCtx("value")));
                assertTrue(ex.getMessage().contains("duplicated"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mutate on a non-duplicated Vert.x context throws IllegalStateException")
    void mutateOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> ContextValues.mutate(StringCtx.class, () -> new StringCtx("init"), s -> {}));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mutateIfPresent on a non-duplicated Vert.x context throws IllegalStateException")
    void mutateIfPresentOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(
                        IllegalStateException.class, () -> ContextValues.mutateIfPresent(StringCtx.class, s -> {}));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("remove on a non-duplicated Vert.x context throws IllegalStateException")
    void removeOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> ContextValues.remove(StringCtx.class));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bind on a duplicated context succeeds ---

    @Test
    @DisplayName("bind on a duplicated context succeeds; current reads the bound value")
    void bindOnDuplicatedContextSucceeds(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope scope = ContextValues.bind(StringCtx.class, new StringCtx("hello"))) {
                assertTrue(ContextValues.current(StringCtx.class).isPresent());
                assertEquals(
                        new StringCtx("hello"),
                        ContextValues.current(StringCtx.class).orElseThrow());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- mutate initialises on first call, applies consumer on subsequent calls ---

    @Test
    @DisplayName("mutate initialises with supplier on first call; applies consumer on subsequent calls")
    void mutateInitialisesAndAppliesConsumer(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                AtomicInteger counter = new AtomicInteger(0);
                // First call: supplier invoked, counter starts at 0, consumer increments to 1.
                ContextValues.mutate(Counter.class, () -> new Counter(new AtomicInteger()), c -> c.counter()
                        .incrementAndGet());
                assertEquals(
                        1,
                        ContextValues.current(Counter.class)
                                .orElseThrow()
                                .counter()
                                .get());
                // Second call: no supplier invocation, consumer increments existing value to 2.
                ContextValues.mutate(
                        Counter.class,
                        () -> {
                            counter.incrementAndGet(); // must NOT be called
                            return new Counter(new AtomicInteger(99));
                        },
                        c -> c.counter().incrementAndGet());
                assertEquals(
                        2,
                        ContextValues.current(Counter.class)
                                .orElseThrow()
                                .counter()
                                .get());
                assertEquals(0, counter.get(), "supplier must not be called when a value is already bound");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- mutateIfPresent is a no-op when absent ---

    @Test
    @DisplayName("mutateIfPresent is no-op when no value is bound; applies consumer when present")
    void mutateIfPresentNoOpWhenAbsentAppliesWhenPresent(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                AtomicInteger sideEffect = new AtomicInteger(0);
                // No value bound — consumer must not be called.
                ContextValues.mutateIfPresent(Counter.class, c -> sideEffect.incrementAndGet());
                assertEquals(0, sideEffect.get(), "consumer must not be called when no value is bound");

                // Bind a value, then mutateIfPresent should apply.
                try (ContextHolder.Scope scope =
                        ContextValues.bind(Counter.class, new Counter(new AtomicInteger(10)))) {
                    ContextValues.mutateIfPresent(
                            Counter.class, c -> c.counter().addAndGet(5));
                    assertEquals(
                            15,
                            ContextValues.current(Counter.class)
                                    .orElseThrow()
                                    .counter()
                                    .get());
                }
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- remove clears the binding ---

    @Test
    @DisplayName("remove clears the binding; subsequent current returns empty")
    void removeClearsBinding(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                ContextValues.bind(StringCtx.class, new StringCtx("bound")); // intentionally not try-with-resources
                assertTrue(ContextValues.current(StringCtx.class).isPresent());
                ContextValues.remove(StringCtx.class);
                assertFalse(ContextValues.current(StringCtx.class).isPresent());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
