// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.InboundContextInitializer;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link InboundExecutionContextScope}.
 *
 * <p>Uses direct instantiation (no Dagger) wired with a real {@link DefaultContextHolder},
 * {@link ContextScopeBinder}, {@link InboundDispatchScope}, and {@link DurableContextPropagator}
 * backed by an empty registry.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>With no initializers, {@code installDispatch} / {@code installDurable} return a scope
 *       that unwinds on close.
 *   <li>With an initializer that binds a value, both that value and the inbound binding are
 *       present after install and absent after close.
 *   <li>With an initializer that returns noop, the composite still works correctly.
 *   <li>When an initializer throws, the inbound binding is cleaned up before propagating.
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class InboundExecutionContextScopeTest {

    /** Marker type for inbound dispatch context test values. */
    record TestDispatchValue(String id) implements ContextValue {}

    /** Marker type for initializer-installed values. */
    record TestInitValue(String label) implements ContextValue {}

    private DefaultContextHolder holder;
    private ContextScopeBinder binder;
    private InboundDispatchScope inboundDispatchScope;
    private DurableContextPropagator durablePropagator;

    @BeforeEach
    void setUp() {
        holder = new DefaultContextHolder();
        binder = new ContextScopeBinder(holder);
        inboundDispatchScope = new InboundDispatchScope();
        DurableContextMetadataRegistry emptyRegistry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        durablePropagator = new DurableContextPropagator(emptyRegistry, holder, binder);
    }

    /** Runs a task on a duplicated Vert.x context (required for holder writes). */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    // --- installDispatch with no initializers ---

    @Test
    @DisplayName("installDispatch with no initializers returns a scope; close removes inbound bindings")
    void installDispatchNoInitializersClosesCleanly(Vertx vertx, VertxTestContext ctx) {
        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of());

        Map<String, Object> dispatchCtx =
                Map.of(TestDispatchValue.class.getName(), new TestDispatchValue("dispatch-1"));

        runOnDuplicated(vertx, v -> {
            try {
                ContextHolder.Scope s = scope.installDispatch(dispatchCtx, "test");
                assertTrue(
                        holder.current(TestDispatchValue.class).isPresent(),
                        "TestDispatchValue must be bound after installDispatch");
                s.close();
                assertFalse(
                        holder.current(TestDispatchValue.class).isPresent(),
                        "TestDispatchValue must be unbound after scope close");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDispatch with one initializer that binds a value ---

    @Test
    @DisplayName("installDispatch with an initializer binds both dispatch and initializer values; close removes both")
    void installDispatchWithInitializerBindsBothValues(Vertx vertx, VertxTestContext ctx) {
        TestInitValue initValue = new TestInitValue("seeded");
        InboundContextInitializer initializer = initCtx -> holder.bind(TestInitValue.class, initValue);

        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of(initializer));

        Map<String, Object> dispatchCtx =
                Map.of(TestDispatchValue.class.getName(), new TestDispatchValue("dispatch-2"));

        runOnDuplicated(vertx, v -> {
            try {
                ContextHolder.Scope s = scope.installDispatch(dispatchCtx, "test");
                assertTrue(holder.current(TestDispatchValue.class).isPresent(), "TestDispatchValue must be bound");
                assertTrue(
                        holder.current(TestInitValue.class).isPresent(), "TestInitValue must be bound by initializer");
                assertEquals(initValue, holder.current(TestInitValue.class).get());

                s.close();
                assertFalse(holder.current(TestDispatchValue.class).isPresent(), "dispatch value removed on close");
                assertFalse(holder.current(TestInitValue.class).isPresent(), "init value removed on close");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDispatch with initializer returning noop ---

    @Test
    @DisplayName("installDispatch with noop initializer still composes correctly")
    void installDispatchWithNoopInitializerWorks(Vertx vertx, VertxTestContext ctx) {
        InboundContextInitializer noopInitializer = initCtx -> ContextScopes.noop();

        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of(noopInitializer));

        Map<String, Object> dispatchCtx =
                Map.of(TestDispatchValue.class.getName(), new TestDispatchValue("dispatch-3"));

        runOnDuplicated(vertx, v -> {
            try {
                ContextHolder.Scope s = scope.installDispatch(dispatchCtx, "test");
                assertTrue(holder.current(TestDispatchValue.class).isPresent(), "dispatch value bound");
                s.close();
                assertFalse(holder.current(TestDispatchValue.class).isPresent(), "dispatch value removed");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDispatch: initializer throws — inbound binding cleaned up ---

    @Test
    @DisplayName("when initializer throws, inbound binding is cleaned up before propagating exception")
    void installDispatchInitializerThrowsCleansUpInbound(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean throwCalled = new AtomicBoolean(false);
        InboundContextInitializer throwingInitializer = initCtx -> {
            throwCalled.set(true);
            throw new RuntimeException("initializer failure");
        };

        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of(throwingInitializer));

        Map<String, Object> dispatchCtx =
                Map.of(TestDispatchValue.class.getName(), new TestDispatchValue("dispatch-4"));

        runOnDuplicated(vertx, v -> {
            try {
                assertThrows(
                        RuntimeException.class,
                        () -> scope.installDispatch(dispatchCtx, "test"),
                        "exception from initializer must propagate");
                assertTrue(throwCalled.get(), "initializer must have been called");
                // After throw, the inbound binding must have been cleaned up
                assertFalse(
                        holder.current(TestDispatchValue.class).isPresent(),
                        "inbound binding must be cleaned up after initializer throws");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDurable with no initializers ---

    @Test
    @DisplayName("installDurable with empty metadata and no initializers returns a scope that closes cleanly")
    void installDurableNoInitializersClosesCleanly(Vertx vertx, VertxTestContext ctx) {
        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of());

        runOnDuplicated(vertx, v -> {
            try {
                // Empty metadata is fine — propagator.bindFrom on empty returns scope that binds
                // DurablePropagationMetadata only.
                ContextHolder.Scope s = scope.installDurable(DurableMetadata.empty(), "test-durable");
                // DurablePropagationMetadata should be bound (always bound by bindFrom)
                assertTrue(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "DurablePropagationMetadata must be bound");
                s.close();
                assertFalse(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "DurablePropagationMetadata must be unbound after scope close");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDurable with an initializer ---

    @Test
    @DisplayName("installDurable with initializer binds durable metadata and initializer value; close removes both")
    void installDurableWithInitializerBindsBothValues(Vertx vertx, VertxTestContext ctx) {
        TestInitValue initValue = new TestInitValue("durable-seeded");
        InboundContextInitializer initializer = initCtx -> holder.bind(TestInitValue.class, initValue);

        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of(initializer));

        runOnDuplicated(vertx, v -> {
            try {
                ContextHolder.Scope s = scope.installDurable(DurableMetadata.empty(), "test-durable");
                assertTrue(holder.current(DurablePropagationMetadata.class).isPresent(), "durable metadata bound");
                assertTrue(holder.current(TestInitValue.class).isPresent(), "init value bound");

                s.close();
                assertFalse(holder.current(DurablePropagationMetadata.class).isPresent(), "durable metadata removed");
                assertFalse(holder.current(TestInitValue.class).isPresent(), "init value removed");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installDurableAndRun ---

    @Test
    @DisplayName("installDurableAndRun installs the durable scope, runs the action, and closes on async success")
    void installDurableAndRunClosesOnAsyncSuccess(Vertx vertx, VertxTestContext ctx) {
        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of());

        runOnDuplicated(vertx, v -> {
            Future<String> result = scope.installDurableAndRun(DurableMetadata.empty(), "test-durable", () -> {
                assertTrue(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "DurablePropagationMetadata must be bound while the action runs");
                return Future.succeededFuture("ok");
            });

            result.onComplete(ar -> ctx.verify(() -> {
                assertTrue(ar.succeeded());
                assertEquals("ok", ar.result());
                assertFalse(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "DurablePropagationMetadata must be unbound after the action's future completes");
                ctx.completeNow();
            }));
        });
    }

    @Test
    @DisplayName("installDurableAndRun closes the scope when the action's returned future fails")
    void installDurableAndRunClosesOnAsyncFailure(Vertx vertx, VertxTestContext ctx) {
        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of());
        RuntimeException asyncFailure = new RuntimeException("action failed asynchronously");

        runOnDuplicated(vertx, v -> {
            Future<String> result = scope.installDurableAndRun(
                    DurableMetadata.empty(), "test-durable", () -> Future.failedFuture(asyncFailure));

            result.onComplete(ar -> ctx.verify(() -> {
                assertTrue(ar.failed());
                assertSame(asyncFailure, ar.cause());
                assertFalse(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "DurablePropagationMetadata must be unbound after the action's future fails");
                ctx.completeNow();
            }));
        });
    }

    @Test
    @DisplayName("installDurableAndRun converts a synchronous action throw into a failed Future and still closes"
            + " the scope (CORE-001 sync-throw lesson)")
    void installDurableAndRunHandlesSynchronousThrow(Vertx vertx, VertxTestContext ctx) {
        InboundExecutionContextScope scope =
                new InboundExecutionContextScope(inboundDispatchScope, durablePropagator, Set.of());
        RuntimeException syncThrow = new RuntimeException("action threw synchronously");

        runOnDuplicated(vertx, v -> {
            Future<String> result;
            try {
                result = scope.installDurableAndRun(DurableMetadata.empty(), "test-durable", () -> {
                    throw syncThrow;
                });
            } catch (RuntimeException e) {
                ctx.failNow(new AssertionError(
                        "installDurableAndRun must convert a synchronous action throw into a failed Future, not"
                                + " propagate it as a thrown exception",
                        e));
                return;
            }

            result.onComplete(ar -> ctx.verify(() -> {
                assertTrue(ar.failed());
                assertSame(syncThrow, ar.cause());
                assertFalse(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "the durable scope must close and restore prior (absent) holder state even on a"
                                + " synchronous action throw");
                ctx.completeNow();
            }));
        });
    }
}
