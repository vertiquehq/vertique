// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the runtime enforcement guards on the context value installation paths.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link DefaultContextHolder#installScoped(Map)} — rejects non-{@link ContextValue} values</li>
 *   <li>{@link DefaultContextHolder#bindSnapshot(ContextSnapshot)} — rejects non-{@link ContextValue} values</li>
 *   <li>{@link DefaultContextHolder#installScopedAuthoritative(Map, Set)} — rejects non-{@link ContextValue} values</li>
 *   <li>Null-value and null-key inputs — treated as malformed carrier, not as delete; rejected by the pre-pass before any mutation</li>
 *   <li>Atomicity: the pre-pass validation fires before any mutation so a mixed batch (including a null key or a null removeKey) leaves no partial state</li>
 *   <li>{@link ContextScopeBinder#bindAll(Map)} pairing pre-pass: value must be an instance of its declared key type</li>
 *   <li>{@link ContextValues#mutate} null-argument rejection</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextValueEnforcementTest {

    // --- Helper ---

    /**
     * Returns a mutable {@link HashMap} with the single entry ({@code k}, {@code v}).
     *
     * <p>Uses a mutable map rather than {@code Map.of} because {@code Map.of} rejects {@code null}
     * values, but several tests need to supply a map that contains a present key with a null value
     * to assert that null is treated as a malformed carrier (never as a delete).
     *
     * @param k the key
     * @param v the value; may be {@code null}
     * @return a new mutable map containing the single entry
     */
    private static Map<String, Object> mutableMap(String k, Object v) {
        var m = new java.util.HashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    /**
     * Returns {@code null} as a {@link StringCtx} reference, bypassing IDE null-flow analysis.
     * Used to supply a null to {@link ContextValues#mutate} so the runtime guard is tested
     * without triggering a compile-time {@code @NonNull} warning on a bare null literal.
     *
     * @return {@code null}
     */
    @SuppressWarnings("ConstantConditions")
    private static StringCtx returnsNull() {
        return null;
    }

    // --- installScoped: non-ContextValue value ---

    @Test
    @DisplayName("installScoped with a non-ContextValue value throws IAE naming the key")
    void installScopedRejectsNonContextValue(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScoped(mutableMap("some.Key", "not-a-context-value")));
                assertTrue(
                        ex.getMessage().contains("some.Key"),
                        "exception message must name the offending key; was: " + ex.getMessage());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bindSnapshot: non-ContextValue value ---

    @Test
    @DisplayName("bindSnapshot with a non-ContextValue value in the snapshot throws IAE")
    void bindSnapshotRejectsNonContextValue(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.bindSnapshot(new ContextSnapshot(mutableMap("some.Key", "nope"))));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- installScopedAuthoritative: non-ContextValue value ---

    @Test
    @DisplayName("installScopedAuthoritative with a non-ContextValue binding throws IAE")
    void installScopedAuthoritativeRejectsNonContextValue(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScopedAuthoritative(
                                mutableMap("some.Key", "nope"), Set.of()));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- null as malformed carrier (not delete) ---

    @Test
    @DisplayName("installScoped with a present key mapped to null throws IAE naming the key")
    void installScopedRejectsNullValue(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScoped(mutableMap("null.Key", null)));
                String msg = ex.getMessage();
                assertTrue(msg.contains("null.Key"), "message must name the key; was: " + msg);
                assertTrue(msg.contains("null"), "message must mention null; was: " + msg);
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("ContextSnapshot constructor rejects a map with a null value (Map.copyOf guard)")
    void snapshotConstructorRejectsNullValue() {
        // ContextSnapshot(Map) calls Map.copyOf which throws NullPointerException on null values.
        // The null-value rejection therefore happens at snapshot construction time, before
        // bindSnapshot is ever reached. No Vert.x context is needed — this is a pure-Java guard.
        assertThrows(
                NullPointerException.class,
                () -> new ContextSnapshot(mutableMap("null.Key", null)),
                "ContextSnapshot constructor must reject a map entry with a null value");
    }

    @Test
    @DisplayName("installScopedAuthoritative with a present key mapped to null throws IAE naming the key")
    void installScopedAuthoritativeRejectsNullValue(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                IllegalArgumentException ex = assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScopedAuthoritative(mutableMap("null.Key", null), Set.of()));
                String msg = ex.getMessage();
                assertTrue(msg.contains("null.Key"), "message must name the key; was: " + msg);
                assertTrue(msg.contains("null"), "message must mention null; was: " + msg);
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- Atomicity: installScopedRaw pre-pass fires before any mutation ---

    @Test
    @DisplayName("installScoped with a mixed valid/invalid batch throws IAE and installs nothing")
    void installScopedAtomicityNothingInstalledOnRejection(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                // Build a batch with one valid and one invalid entry.
                Map<String, Object> batch = new HashMap<>();
                batch.put(StringCtx.class.getName(), new StringCtx("v"));
                batch.put("bad.Key", "nope");

                assertThrows(IllegalArgumentException.class, () -> DefaultContextHolder.installScoped(batch));

                // The valid entry must NOT have been installed — the pre-pass rejects atomically.
                DefaultContextHolder holder = new DefaultContextHolder();
                assertFalse(
                        holder.current(StringCtx.class).isPresent(),
                        "valid entry must not be installed when the batch is rejected atomically");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- Atomicity: installScopedAuthoritative — ambient not cleared on rejection ---

    @Test
    @DisplayName("installScopedAuthoritative with invalid batch throws IAE and leaves ambient value intact")
    void installScopedAuthoritativeAtomicityAmbientUnchanged(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                DefaultContextHolder holder = new DefaultContextHolder();
                // Bind an ambient value on the current duplicated context.
                holder.bind(StringCtx.class, new StringCtx("ambient"));
                assertTrue(holder.current(StringCtx.class).isPresent(), "ambient value must be present after bind");

                // Attempt an authoritative install with an invalid binding; also lists StringCtx
                // as a key to remove.
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScopedAuthoritative(
                                mutableMap("bad.Key", "nope"), Set.of(StringCtx.class.getName())));

                // The ambient value must still be present — the rejected install changed nothing.
                assertTrue(
                        holder.current(StringCtx.class).isPresent(),
                        "ambient value must still be present after rejected authoritative install");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- null keys are malformed erased input (atomic rejection) ---

    @Test
    @DisplayName("installScoped with a null key (valid entry preceding) throws IAE and installs nothing")
    void installScopedRejectsNullKeyAtomically(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                // Valid entry first, then a present null key whose value is itself a valid
                // ContextValue — ordered so a non-atomic install would write the valid entry before
                // failing on the null key, leaving a partial install with no scope to unwind.
                Map<String, Object> batch = new LinkedHashMap<>();
                batch.put(StringCtx.class.getName(), new StringCtx("v"));
                batch.put(null, new IntCtx(1));

                assertThrows(IllegalArgumentException.class, () -> DefaultContextHolder.installScoped(batch));

                DefaultContextHolder holder = new DefaultContextHolder();
                assertFalse(
                        holder.current(StringCtx.class).isPresent(),
                        "preceding valid entry must not be installed when a later null key is rejected");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("installScopedAuthoritative with a null key in bindings throws IAE")
    void installScopedAuthoritativeRejectsNullKeyInBindings(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScopedAuthoritative(
                                mutableMap(null, new StringCtx("v")), Set.of()));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("installScopedAuthoritative with a null in removeKeys throws IAE and clears nothing")
    void installScopedAuthoritativeRejectsNullRemoveKeyAtomically(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                DefaultContextHolder holder = new DefaultContextHolder();
                holder.bind(StringCtx.class, new StringCtx("ambient"));

                Set<String> removeKeys = new LinkedHashSet<>();
                removeKeys.add(StringCtx.class.getName());
                removeKeys.add(null);

                assertThrows(
                        IllegalArgumentException.class,
                        () -> DefaultContextHolder.installScopedAuthoritative(
                                mutableMap(IntCtx.class.getName(), new IntCtx(1)), removeKeys));

                // Atomic: the IntCtx binding is not installed and the ambient StringCtx is not cleared.
                assertTrue(
                        holder.current(StringCtx.class).isPresent(),
                        "ambient value must survive a rejected authoritative batch with a null removeKey");
                assertFalse(
                        holder.current(IntCtx.class).isPresent(),
                        "no binding may be installed when the authoritative batch is rejected");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bindAll pairing pre-pass ---

    @Test
    @DisplayName("bindAll rejects a map where value is not an instance of its key type")
    void bindAllRejectsMismatchedPairing(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                DefaultContextHolder holder = new DefaultContextHolder();
                ContextScopeBinder binder = new ContextScopeBinder(holder);

                // StringCtx key but IntCtx value — type mismatch
                Map<Class<? extends ContextValue>, ContextValue> bad = new HashMap<>();
                bad.put(StringCtx.class, new IntCtx(1));

                assertThrows(
                        IllegalArgumentException.class,
                        () -> binder.bindAll(bad),
                        "bindAll must throw when the value is not an instance of the declared key type");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindAll with a correctly typed entry binds the value; scope close removes it")
    void bindAllPositiveCase(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                DefaultContextHolder holder = new DefaultContextHolder();
                ContextScopeBinder binder = new ContextScopeBinder(holder);

                Map<Class<? extends ContextValue>, ContextValue> good = new HashMap<>();
                good.put(StringCtx.class, new StringCtx("ok"));

                ContextHolder.Scope scope = assertDoesNotThrow(() -> binder.bindAll(good));
                assertTrue(holder.current(StringCtx.class).isPresent(), "value must be bound after bindAll");

                scope.close();
                assertFalse(holder.current(StringCtx.class).isPresent(), "value must be absent after scope close");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- composite binder null Class-key rejection (fail-fast, before any holder write) ---

    @Test
    @DisplayName("bindAll rejects a null Class key with IllegalArgumentException")
    void bindAllRejectsNullKey() {
        ContextScopeBinder binder = new ContextScopeBinder(new DefaultContextHolder());
        Map<Class<? extends ContextValue>, ContextValue> bad = new HashMap<>();
        bad.put(null, new StringCtx("x"));
        assertThrows(IllegalArgumentException.class, () -> binder.bindAll(bad));
    }

    @Test
    @DisplayName("bindAllAuthoritative rejects a null Class key in bindings with IllegalArgumentException")
    void bindAllAuthoritativeRejectsNullKeyInBindings() {
        ContextScopeBinder binder = new ContextScopeBinder(new DefaultContextHolder());
        Map<Class<?>, Object> bad = new HashMap<>();
        bad.put(null, new StringCtx("x"));
        assertThrows(IllegalArgumentException.class, () -> binder.bindAllAuthoritative(bad, Set.of()));
    }

    @Test
    @DisplayName("bindAllAuthoritative rejects a null type in clearTypes with IllegalArgumentException")
    void bindAllAuthoritativeRejectsNullClearType() {
        ContextScopeBinder binder = new ContextScopeBinder(new DefaultContextHolder());
        Set<Class<?>> clearTypes = new LinkedHashSet<>();
        clearTypes.add(null);
        assertThrows(IllegalArgumentException.class, () -> binder.bindAllAuthoritative(new HashMap<>(), clearTypes));
    }

    // --- mutate null-argument robustness ---

    @Test
    @DisplayName("mutate with a null type throws NullPointerException")
    void mutateNullTypethrowsNpe(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertThrows(
                        NullPointerException.class,
                        () -> ContextValues.mutate(null, () -> new StringCtx("x"), c -> {}));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mutate whose supplier returns null throws NullPointerException mentioning the type name")
    void mutateNullSupplierResultThrowsNpeWithTypeName(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                NullPointerException ex = assertThrows(
                        NullPointerException.class,
                        () -> ContextValues.mutate(StringCtx.class, () -> returnsNull(), c -> {}));
                assertTrue(
                        ex.getMessage().contains("StringCtx"),
                        "NPE message must name the type; was: " + ex.getMessage());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
