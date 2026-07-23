// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link DefaultContextHolder}.
 *
 * <p>The holder enforces a single invariant: writes are only valid on a duplicated Vert.x
 * context. Per-dispatch isolation therefore comes from each transport boundary entering its own
 * {@code ContextInternal.duplicate()} before invoking
 * {@link ContextHolder#bind(Class, Object)} or {@code DefaultContextHolder.installScoped(...)}.
 * Within a single duplicated context the holder offers ordinary nested LIFO scopes.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextHolderTest {

    private final DefaultContextHolder holder = new DefaultContextHolder();

    // --- write-side guard ---

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
                        IllegalStateException.class, () -> holder.bind(StringCtx.class, new StringCtx("value")));
                assertTrue(ex.getMessage().contains("duplicated"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bind outside any Vert.x context throws IllegalStateException")
    void bindOutsideAnyContextThrows() {
        assertThrows(IllegalStateException.class, () -> holder.bind(StringCtx.class, new StringCtx("value")));
    }

    @Test
    @DisplayName("bind on a duplicated Vert.x context succeeds and current returns the bound value")
    void bindOnDuplicatedContextSucceeds(Vertx vertx, VertxTestContext ctx) {
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal dup = root.duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("hello"))) {
                assertTrue(holder.current(StringCtx.class).isPresent());
                assertEquals(
                        new StringCtx("hello"), holder.current(StringCtx.class).orElseThrow());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- nested LIFO scoping on a duplicated context ---

    @Test
    @DisplayName("nested bind/close restores the prior value on the same duplicated context (LIFO)")
    void nestedBindRestoresPriorValueLifo(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                try (ContextHolder.Scope outer = holder.bind(StringCtx.class, new StringCtx("outer"))) {
                    assertEquals(
                            new StringCtx("outer"),
                            holder.current(StringCtx.class).orElseThrow());
                    try (ContextHolder.Scope inner = holder.bind(StringCtx.class, new StringCtx("inner"))) {
                        assertEquals(
                                new StringCtx("inner"),
                                holder.current(StringCtx.class).orElseThrow());
                    }
                    assertEquals(
                            new StringCtx("outer"),
                            holder.current(StringCtx.class).orElseThrow());
                }
                assertFalse(holder.current(StringCtx.class).isPresent());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- event bus consumer dispatch enters a duplicated context ---

    @Test
    @DisplayName("event-bus consumer dispatch already runs on a duplicated Vert.x context")
    void eventBusConsumerDispatchesOnDuplicatedContext(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Boolean> isDup = new AtomicReference<>();
        MessageConsumer<String> consumer = vertx.eventBus().consumer("test.address", message -> {
            try {
                Context current = Vertx.currentContext();
                isDup.set(current instanceof ContextInternal internal && internal.isDuplicate());
                // The substrate's write guard must accept this context — proving end-to-end that
                // Vert.x's event-bus consumer dispatch is the boundary that already gives us a
                // duplicated context.
                try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("from-consumer"))) {
                    assertEquals(
                            new StringCtx("from-consumer"),
                            holder.current(StringCtx.class).orElseThrow());
                }
                message.reply("ok");
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
        consumer.completion().onComplete(ar -> {
            if (ar.failed()) {
                ctx.failNow(ar.cause());
                return;
            }
            vertx.eventBus().request("test.address", "ping").onComplete(reply -> {
                try {
                    if (reply.failed()) {
                        ctx.failNow(reply.cause());
                        return;
                    }
                    assertEquals(
                            Boolean.TRUE,
                            isDup.get(),
                            "Vert.x event-bus consumer must dispatch on a duplicated context");
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });
        });
    }

    // --- isolation between separate duplicated contexts ---

    @Test
    @DisplayName("two duplicated contexts of the same root do not share holder values")
    void twoDuplicatedContextsDoNotShareValues(Vertx vertx, VertxTestContext ctx) {
        ContextInternal root = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal dupA = root.duplicate();
        ContextInternal dupB = root.duplicate();
        assertNotSame(dupA, dupB, "duplicate() must return distinct context instances");

        // Keep A's binding open while B runs and verifies isolation. We hold A's scope in a local
        // and only close it after B has completed its checks; otherwise A's scope would exit
        // before B's runOnContext fires and the test would tell us nothing about cross-context
        // isolation.
        VertxTestContext.ExecutionBlock onA = () -> {
            ContextHolder.Scope scopeA = holder.bind(StringCtx.class, new StringCtx("A"));
            try {
                assertEquals(new StringCtx("A"), holder.current(StringCtx.class).orElseThrow());
                dupB.runOnContext(vb -> {
                    try {
                        assertSame(dupB, Vertx.currentContext());
                        assertFalse(
                                holder.current(StringCtx.class).isPresent(),
                                "duplicated context B must not see context A's bound value");
                        // B may now bind its own value without observing A's:
                        try (ContextHolder.Scope scopeB = holder.bind(StringCtx.class, new StringCtx("B"))) {
                            assertEquals(
                                    new StringCtx("B"),
                                    holder.current(StringCtx.class).orElseThrow());
                        }
                        // Re-enter dupA after B's check to confirm A's binding is still live.
                        dupA.runOnContext(va2 -> {
                            try {
                                assertEquals(
                                        new StringCtx("A"),
                                        holder.current(StringCtx.class).orElseThrow(),
                                        "A's binding must remain live while B's runOnContext executes");
                                scopeA.close();
                                ctx.completeNow();
                            } catch (Throwable t) {
                                scopeA.close();
                                ctx.failNow(t);
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            } catch (Throwable t) {
                scopeA.close();
                ctx.failNow(t);
            }
        };
        dupA.runOnContext(va -> {
            try {
                onA.apply();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- null rejection ---

    @Test
    @DisplayName("bind with null type throws NullPointerException")
    void bindNullTypeThrowsNpe(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<StringCtx> nullType = (Class<StringCtx>) (Class) null;
                assertThrows(NullPointerException.class, () -> holder.bind(nullType, new StringCtx("value")));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bind with null value throws NullPointerException")
    void bindNullValueThrowsNpe(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                StringCtx nullValue = null;
                assertThrows(NullPointerException.class, () -> holder.bind(StringCtx.class, nullValue));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("current outside a Vert.x context returns Optional.empty")
    void currentOutsideContextReturnsEmpty() {
        assertFalse(holder.current(String.class).isPresent());
    }

    // --- idempotent close ---

    @Test
    @DisplayName("double close of a scope is harmless (idempotent)")
    void doubleCloseIsIdempotent(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("value"));
                scope.close();
                scope.close();
                assertFalse(holder.current(StringCtx.class).isPresent());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
