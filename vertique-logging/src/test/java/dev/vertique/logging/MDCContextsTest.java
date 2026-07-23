// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link MDCContexts}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Write operations ({@code put}, {@code putAll}, {@code remove}, {@code removeAll},
 *       {@code clear}, {@code bindAll} with a non-empty map) throw {@link IllegalStateException}
 *       on a non-duplicated Vert.x context.
 *   <li>{@code get} and {@code copy} are lenient: they return {@code null} / empty map outside a
 *       Vert.x context.
 *   <li>{@code bindAll(emptyMap)} is a no-op and does not require a duplicated context.
 *   <li>Full MDC round-trip on a duplicated context.
 *   <li>{@code bindAll} snapshots only its keys; keys outside the bound set are unaffected by
 *       close.
 *   <li>Nested {@code bindAll} LIFO restore.
 *   <li>Idempotent {@code bindAll} scope close.
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MDCContextsTest {

    // --- lenient reads outside any Vert.x context ---

    @Test
    @DisplayName("get outside any Vert.x context returns null")
    void getOutsideContextReturnsNull() {
        assertNull(MDCContexts.get("any"));
    }

    @Test
    @DisplayName("copy outside any Vert.x context returns an empty map")
    void copyOutsideContextReturnsEmpty() {
        assertTrue(MDCContexts.copy().isEmpty());
    }

    // --- write guard on non-duplicated context ---

    @Test
    @DisplayName("put on a non-duplicated Vert.x context throws IllegalStateException")
    void putOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> MDCContexts.put("k", "v"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("putAll on a non-duplicated Vert.x context throws IllegalStateException")
    void putAllOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> MDCContexts.putAll(Map.of("k", "v")));
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
                assertThrows(IllegalStateException.class, () -> MDCContexts.remove("k"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("removeAll on a non-duplicated Vert.x context throws IllegalStateException")
    void removeAllOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> MDCContexts.removeAll(List.of("k")));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("clear on a non-duplicated Vert.x context throws IllegalStateException")
    void clearOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, MDCContexts::clear);
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindAll(non-empty) on a non-duplicated Vert.x context throws IllegalStateException")
    void bindAllNonEmptyOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> MDCContexts.bindAll(Map.of("k", "v")));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindAll(emptyMap) does not require a duplicated context and returns a no-op scope")
    void bindAllEmptyMapIsNoOp(Vertx vertx, VertxTestContext ctx) {
        // Called on the non-duplicated root context — must not throw.
        vertx.runOnContext(v -> {
            try {
                ContextHolder.Scope scope = MDCContexts.bindAll(Map.of());
                scope.close(); // idempotent, must not throw
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- full round-trip on a duplicated context ---

    @Test
    @DisplayName("put / get / copy / putAll / remove / clear round-trip on a duplicated context")
    void roundTripOnDuplicatedContext(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                // put / get
                MDCContexts.put("a", "1");
                assertEquals("1", MDCContexts.get("a"));

                // copy contains the entry
                Map<String, String> copy = MDCContexts.copy();
                assertEquals(Map.of("a", "1"), copy);

                // putAll merges
                MDCContexts.putAll(Map.of("b", "2", "c", "3"));
                assertEquals("2", MDCContexts.get("b"));
                assertEquals("3", MDCContexts.get("c"));

                // remove removes one key
                MDCContexts.remove("b");
                assertNull(MDCContexts.get("b"));
                assertEquals("1", MDCContexts.get("a"));

                // removeAll removes multiple keys
                MDCContexts.removeAll(List.of("a", "c"));
                assertNull(MDCContexts.get("a"));
                assertNull(MDCContexts.get("c"));
                assertTrue(MDCContexts.copy().isEmpty());

                // put something back and clear
                MDCContexts.put("x", "42");
                MDCContexts.clear();
                assertNull(MDCContexts.get("x"));
                assertTrue(MDCContexts.copy().isEmpty());

                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bindAll snapshots only its keys ---

    @Test
    @DisplayName("bindAll restores only its keys on close; mutations outside the bound set are preserved")
    void bindAllRestoresOnlyBoundKeys(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                // Pre-bind some keys.
                MDCContexts.put("pre-a", "pa");
                MDCContexts.put("pre-b", "pb");

                // bindAll with one overlapping key and one new key.
                ContextHolder.Scope scope = MDCContexts.bindAll(Map.of("pre-a", "bound-a", "new-x", "nx"));

                // New map is visible.
                assertEquals("bound-a", MDCContexts.get("pre-a"));
                assertEquals("nx", MDCContexts.get("new-x"));
                assertEquals("pb", MDCContexts.get("pre-b"), "unrelated key must remain");

                // Mutate a key outside the bindAll set.
                MDCContexts.put("unrelated", "u1");

                scope.close();

                // Prior values restored for the bound keys.
                assertEquals("pa", MDCContexts.get("pre-a"), "pre-a must be restored to prior value");
                assertNull(MDCContexts.get("new-x"), "new-x must be removed (was absent before bindAll)");

                // Unrelated key and unrelated mutation must be preserved.
                assertEquals("pb", MDCContexts.get("pre-b"), "pre-b must be untouched");
                assertEquals("u1", MDCContexts.get("unrelated"), "mutation outside bound set must survive scope close");

                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- nested bindAll LIFO restore ---

    @Test
    @DisplayName("nested bindAll restores LIFO: inner scope close does not affect outer binding")
    void nestedBindAllLifoRestore(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDCContexts.put("k", "outer-original");

                ContextHolder.Scope outer = MDCContexts.bindAll(Map.of("k", "outer-bound"));
                assertEquals("outer-bound", MDCContexts.get("k"));

                ContextHolder.Scope inner = MDCContexts.bindAll(Map.of("k", "inner-bound"));
                assertEquals("inner-bound", MDCContexts.get("k"));

                inner.close();
                assertEquals("outer-bound", MDCContexts.get("k"), "after inner close, outer binding must be visible");

                outer.close();
                assertEquals(
                        "outer-original", MDCContexts.get("k"), "after outer close, original value must be restored");

                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- idempotent bindAll scope close ---

    @Test
    @DisplayName("double close of a bindAll scope is harmless")
    void bindAllScopeCloseIsIdempotent(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDCContexts.put("k", "before");
                ContextHolder.Scope scope = MDCContexts.bindAll(Map.of("k", "during"));
                assertEquals("during", MDCContexts.get("k"));
                scope.close();
                assertEquals("before", MDCContexts.get("k"));
                scope.close(); // second close must not undo the restore
                assertEquals("before", MDCContexts.get("k"), "second close must be a no-op");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bindAll with null entries ---

    @Test
    @DisplayName("bindAll(null) returns a no-op scope and does not throw")
    void bindAllNullIsNoOp(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            try {
                ContextHolder.Scope scope = MDCContexts.bindAll(null);
                scope.close();
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- verify absent key is treated as absent in priorPresent snapshot ---

    @Test
    @DisplayName("bindAll with a new key (not previously present) restores absence on close")
    void bindAllRestoresAbsenceForNewKey(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertNull(MDCContexts.get("fresh"), "fresh must start absent");
                ContextHolder.Scope scope = MDCContexts.bindAll(Map.of("fresh", "v"));
                assertEquals("v", MDCContexts.get("fresh"));
                scope.close();
                assertNull(MDCContexts.get("fresh"), "fresh must be absent after scope close");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- scope restores against the install-time context, not the current context on close ---

    @Test
    @DisplayName("bindAll scope close restores its install-time context, not whatever is current on close")
    void bindAllScopeRestoresInstallContext(Vertx vertx, VertxTestContext ctx) {
        // Context A binds an MDC key, then we move to a separate duplicated context B and close the
        // scope FROM B. The scope must restore A's binding (remove "key") even though the current
        // context on close is B. This pins the contract that close() operates on the install-time
        // context, mirroring DefaultContextHolder.MultiKeyScope.
        ContextInternal dupA = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        ContextInternal dupB = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dupA.runOnContext(v -> {
            try {
                assertNull(MDCContexts.get("k"));
                ContextHolder.Scope scope = MDCContexts.bindAll(Map.of("k", "v"));
                assertEquals("v", MDCContexts.get("k"), "k must be bound on context A");
                // Switch to a *different* duplicated context and close the scope from there.
                dupB.runOnContext(v2 -> {
                    try {
                        assertNull(MDCContexts.get("k"), "context B starts with no MDC binding");
                        scope.close();
                        // The scope must have restored context A, not affected context B.
                        assertNull(MDCContexts.get("k"), "context B's MDC is still empty (no binding installed)");
                        dupA.runOnContext(v3 -> {
                            try {
                                assertNull(
                                        MDCContexts.get("k"),
                                        "context A's MDC binding must be restored (key absent) after close ran from B");
                                ctx.completeNow();
                            } catch (Throwable t) {
                                ctx.failNow(t);
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
