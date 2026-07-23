// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.ContextValueAdapter;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.spi.context.storage.ContextLocal;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Default {@link ContextHolder} implementation backed by a Vert.x {@link ContextLocal} slot.
 *
 * <p><b>Per-dispatch isolation via duplicated contexts.</b> Each FQCN key maps directly to its
 * currently-bound value in a per-context map. Concurrent dispatches must each run on their own
 * {@code ContextInternal.duplicate()}; the write-side guard
 * {@link #requireDuplicatedContextForWrite()} enforces this. Within a single duplicated context,
 * nested {@link Scope#close()} calls follow LIFO order: each scope captures a snapshot of prior
 * values at the keys it overwrites and restores those snapshots on close.
 *
 * <p>The context-local slot is registered once during Vert.x bootstrap by
 * {@link ContextLocalServiceProvider}. Each Vert.x context lazily initializes a
 * {@link ConcurrentHashMap} as its per-key value map; the map stays attached to the context for
 * its lifetime.
 *
 * <p>Read operations ({@link #current(Class)}, {@link #currentValue(Class)}) return
 * {@link Optional#empty()} outside a Vert.x context. Write operations
 * ({@link #bind(Class, Object)}, {@link #installScoped(Map)}) throw
 * {@link IllegalStateException} when invoked outside a Vert.x context or on a non-duplicated
 * context — the dispatch boundary is responsible for entering a duplicated context before writes.
 */
@Singleton
public final class DefaultContextHolder implements ContextHolder {

    private static volatile ContextLocal<Map<String, Object>> CONTEXT_LOCAL;

    // --- Lifecycle ---

    /**
     * Returns {@code true} if the context-local slot has already been initialized by a prior
     * {@link io.vertx.core.Vertx#vertx()} call in this JVM. Used by
     * {@link ContextLocalServiceProvider} to guard against double registration when multiple
     * Vert.x instances are created in the same JVM (e.g. integration-test suites that spin up a
     * second instance).
     *
     * @return {@code true} if the slot is already registered
     */
    static boolean isContextLocalInitialized() {
        return CONTEXT_LOCAL != null;
    }

    /**
     * Atomically initializes the context-local slot exactly once per JVM. If the slot is already
     * registered this is a no-op; otherwise {@code registrar} is invoked — while holding the class
     * monitor — to register and return the slot, which is then published.
     *
     * <p>The double-checked guard is the correctness-critical part: two threads concurrently
     * creating a {@code Vertx} instance (e.g. parallel JUnit test classes each booting their own
     * {@code Vertx}) both run this method, but only the thread that wins the lock invokes
     * {@code registrar}. {@code registrar} is the only place
     * {@link io.vertx.core.spi.context.storage.ContextLocal#registerLocal} is called, so the loser
     * never allocates a JVM-global slot index. A plain check-then-set would let both threads
     * register, burning two slots and leaving {@link #CONTEXT_LOCAL} pointing at one — the other
     * slot's contexts would then fail with {@code Invalid key index}. {@code CONTEXT_LOCAL} stays
     * {@code volatile} so the racy fast-path read observes the published slot without locking.
     *
     * @param registrar supplier that registers and returns the slot; invoked at most once per JVM,
     *                  only by the thread that wins the registration race; must not be {@code null}
     */
    static void initContextLocalIfAbsent(Supplier<ContextLocal<Map<String, Object>>> registrar) {
        if (CONTEXT_LOCAL == null) { // racy fast-path read (volatile)
            synchronized (DefaultContextHolder.class) {
                if (CONTEXT_LOCAL == null) { // confirmed under lock
                    CONTEXT_LOCAL = registrar.get(); // registerLocal() called by the winner only
                }
            }
        }
    }

    @Inject
    public DefaultContextHolder() {}

    // --- ContextHolder ---

    @Override
    public <T> Optional<T> current(Class<T> type) {
        Object value = currentRaw(type.getName());
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return Optional.of(typed);
    }

    @Override
    public <T extends ContextValue> Scope bind(Class<T> type, T value) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (CONTEXT_LOCAL == null) {
            throw new IllegalStateException(
                    "ContextLocal slot not initialized — ContextLocalServiceProvider must be registered as a Vert.x SPI");
        }
        Context ctx = requireDuplicatedContextForWrite();
        String key = type.getName();
        Map<String, Object> values = currentMap(ctx);
        boolean priorPresent = values.containsKey(key);
        Object priorValue = priorPresent ? values.get(key) : null;
        values.put(key, value);
        return new SingleKeyScope(ctx, key, priorPresent, priorValue);
    }

    // --- Static helpers ---

    /**
     * Binds the given value under the given type for the current Vert.x duplicated context,
     * returning a scope that restores the prior binding on close.
     *
     * <p>Package-private — used by {@link ContextValues#bind(Class, Object)} so that the write
     * guard is not bypassed.
     *
     * @param type  the context value type; must not be {@code null}
     * @param value the value to bind; must not be {@code null}
     * @param <T>   the value type
     * @return a scope that restores the prior binding on close
     */
    static <T extends ContextValue> ContextHolder.Scope bindStatic(Class<T> type, T value) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (CONTEXT_LOCAL == null) {
            throw new IllegalStateException(
                    "ContextLocal slot not initialized — ContextLocalServiceProvider must be registered as a Vert.x SPI");
        }
        Context ctx = requireDuplicatedContextForWrite();
        String key = type.getName();
        Map<String, Object> values = currentMap(ctx);
        boolean priorPresent = values.containsKey(key);
        Object priorValue = priorPresent ? values.get(key) : null;
        values.put(key, value);
        return new SingleKeyScope(ctx, key, priorPresent, priorValue);
    }

    /**
     * Returns the currently bound value for the given type using the static context-local slot.
     * Used by {@code DispatchContext.current(...)}-style accessors that read without injection.
     */
    public static <T> Optional<T> currentValue(Class<T> type) {
        Object value = currentRaw(type.getName());
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return Optional.of(typed);
    }

    /**
     * Returns the raw value at the given FQCN key without any type check, or {@code null} if no
     * value is bound or no Vert.x context is active.
     *
     * <p>Package-private — used by {@link InboundDispatchScope} for the inbound dispatcher's
     * by-FQCN-string raw read (the {@code SecurityContext} subtype injection path).
     */
    static Object currentRawValueByKey(String key) {
        return currentRaw(key);
    }

    /**
     * Installs the given FQCN-keyed values for the current Vert.x context as one logical
     * dispatch scope. Captures a snapshot of prior values at each key and restores them on
     * {@link Scope#close()}. Caller must be on a duplicated Vert.x context.
     *
     * <p>Package-private — used by {@link InboundDispatchScope}.
     */
    static ContextHolder.Scope installScoped(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return NoOpScope.INSTANCE;
        }
        if (CONTEXT_LOCAL == null) {
            throw new IllegalStateException(
                    "ContextLocal slot not initialized — ContextLocalServiceProvider must be registered as a Vert.x SPI");
        }
        Context ctx = requireDuplicatedContextForWrite();
        return installScopedRaw(ctx, values);
    }

    /**
     * Authoritative install for durable-boundary restoration: installs every entry in
     * {@code bindings} AND clears every key in {@code removeKeys}, capturing the prior value at
     * each affected key so {@link Scope#close()} restores the holder to its pre-call state
     * (including re-installing any value that was cleared by this call).
     *
     * <p>Use this instead of {@link #installScoped(Map)} when the durable boundary's metadata
     * represents an authoritative snapshot — every registered durable type that is absent from the
     * metadata must be cleared for the scope's lifetime so that any subsequent {@code capture(...)}
     * or {@code mergeCaptured(...)} during the scope sees only the values that originally crossed
     * the durable boundary, not whatever ambient context happened to be live at the call site.
     *
     * <p>Keys that appear in both {@code bindings} and {@code removeKeys} are treated as bindings
     * — the value wins. {@code removeKeys} may name keys that are not currently bound; those are
     * recorded as "was-absent" so close is still a clean no-op for them.
     *
     * <p>If both maps are empty, a no-op scope is returned and the duplicated-context guard is not
     * exercised (consistent with {@link #installScoped(Map)}).
     *
     * <p>Package-private — used by {@link ContextScopeBinder#bindAllAuthoritative(Map, Set)}.
     *
     * @param bindings   FQCN-keyed values to install
     * @param removeKeys FQCN-keyed keys to clear for the scope's lifetime
     * @return a scope that restores the pre-call state on close
     */
    static ContextHolder.Scope installScopedAuthoritative(Map<String, Object> bindings, Set<String> removeKeys) {
        Objects.requireNonNull(bindings, "bindings must not be null");
        Objects.requireNonNull(removeKeys, "removeKeys must not be null");
        if (bindings.isEmpty() && removeKeys.isEmpty()) {
            return NoOpScope.INSTANCE;
        }
        for (Map.Entry<String, Object> e : bindings.entrySet()) {
            requireContextValue(e.getKey(), e.getValue());
        }
        for (String removeKey : removeKeys) {
            if (removeKey == null) {
                // Reject before any mutation so a malformed removeKeys set cannot NPE mid-clear and
                // leave the bindings already installed without a scope to unwind them.
                throw new IllegalArgumentException("removeKeys must not contain a null key");
            }
        }
        if (CONTEXT_LOCAL == null) {
            throw new IllegalStateException(
                    "ContextLocal slot not initialized — ContextLocalServiceProvider must be registered as a Vert.x SPI");
        }
        Context ctx = requireDuplicatedContextForWrite();
        Map<String, Object> current = currentMap(ctx);

        // Account for keys that are both bound and listed for removal — bindings win.
        int totalUnique = bindings.size();
        for (String k : removeKeys) {
            if (!bindings.containsKey(k)) {
                totalUnique++;
            }
        }
        String[] keys = new String[totalUnique];
        boolean[] priorPresent = new boolean[totalUnique];
        Object[] priorValues = new Object[totalUnique];
        int i = 0;
        for (Map.Entry<String, Object> e : bindings.entrySet()) {
            String key = e.getKey();
            keys[i] = key;
            priorPresent[i] = current.containsKey(key);
            priorValues[i] = priorPresent[i] ? current.get(key) : null;
            current.put(key, e.getValue());
            i++;
        }
        for (String key : removeKeys) {
            if (bindings.containsKey(key)) {
                continue;
            }
            keys[i] = key;
            priorPresent[i] = current.containsKey(key);
            priorValues[i] = priorPresent[i] ? current.get(key) : null;
            current.remove(key);
            i++;
        }
        return new MultiKeyScope(ctx, keys, priorPresent, priorValues);
    }

    // --- Package-private static write helpers (used by ContextValues) ---

    /**
     * Gets or initialises the value bound under {@code type} in the current Vert.x context, then
     * applies the given consumer mutation. If no value is bound, or if the bound value is not an
     * instance of {@code type}, {@code initialValue.get()} is called and the result is stored
     * before the mutation is applied.
     *
     * <p>Fails fast if the current context is not a duplicated Vert.x context.
     *
     * @param type         the context value type key
     * @param initialValue supplier for the initial value when none is bound
     * @param mutation     consumer applied to the (possibly newly initialised) value
     * @param <T>          the value type
     */
    static <T extends ContextValue> void mutate(
            Class<T> type, Supplier<? extends T> initialValue, Consumer<? super T> mutation) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(initialValue, "initialValue must not be null");
        Objects.requireNonNull(mutation, "mutation must not be null");
        Context ctx = requireDuplicatedContextForWrite();
        String key = type.getName();
        Map<String, Object> values = currentMap(ctx);
        Object existing = values.get(key);
        T target;
        if (type.isInstance(existing)) {
            @SuppressWarnings("unchecked")
            T cast = (T) existing;
            target = cast;
        } else {
            target = initialValue.get();
            Objects.requireNonNull(target, "initialValue supplier must not return null for type " + type.getName());
            values.put(key, target);
        }
        mutation.accept(target);
    }

    /**
     * Applies the given consumer mutation to the currently-bound value of {@code type} if — and
     * only if — a value of that type is currently bound. No-ops if the key is absent or holds a
     * value of the wrong type.
     *
     * <p>Fails fast if the current context is not a duplicated Vert.x context.
     *
     * @param type     the context value type key
     * @param mutation consumer applied to the existing value
     * @param <T>      the value type
     */
    static <T extends ContextValue> void mutateIfPresent(Class<T> type, Consumer<? super T> mutation) {
        Context ctx = requireDuplicatedContextForWrite();
        String key = type.getName();
        Map<String, Object> values = currentMap(ctx);
        Object existing = values.get(key);
        if (type.isInstance(existing)) {
            @SuppressWarnings("unchecked")
            T cast = (T) existing;
            mutation.accept(cast);
        }
    }

    /**
     * Variant of {@link #mutateIfPresent(Class, Consumer)} that operates on a specific Vert.x
     * {@link Context} captured at install time rather than {@link Vertx#currentContext()}. Used
     * by scope-close paths that must restore against the same context they installed on,
     * regardless of which context is current when {@code close()} fires.
     *
     * <p>Does NOT require a duplicated context — the caller is restoring prior state, not writing
     * a new binding. No-ops if {@code ctx} is {@code null}, the holder has no per-context map, or
     * the bound value at {@code type} is absent or of the wrong type.
     *
     * <p>Public because feature modules in other packages (e.g. {@code vertique-logging}'s MDC
     * scope close in {@code MDCContexts.MdcKeyScope}) need to mutate the install-time context
     * during scope close. Treat this as a substrate-only escape hatch — application code should
     * use the standard {@link ContextValues} facade.
     *
     * @param ctx      the install-time Vert.x context; may be {@code null}
     * @param type     the context value type key
     * @param mutation consumer applied to the existing value, if any
     * @param <T>      the value type
     */
    public static <T> void mutateIfPresentOnContext(Context ctx, Class<T> type, Consumer<? super T> mutation) {
        if (ctx == null || CONTEXT_LOCAL == null) {
            return;
        }
        Map<String, Object> values = CONTEXT_LOCAL.get(ctx);
        if (values == null) {
            return;
        }
        Object existing = values.get(type.getName());
        if (type.isInstance(existing)) {
            @SuppressWarnings("unchecked")
            T cast = (T) existing;
            mutation.accept(cast);
        }
    }

    /**
     * Removes the binding for the given type from the current Vert.x context's value map.
     *
     * <p>Fails fast if the current context is not a duplicated Vert.x context.
     *
     * @param type the context value type whose binding should be removed
     */
    static void removeKey(Class<?> type) {
        Context ctx = requireDuplicatedContextForWrite();
        Map<String, Object> values = currentMap(ctx);
        values.remove(type.getName());
    }

    /**
     * Captures an immutable snapshot of all values currently bound in the active Vert.x context.
     *
     * <p>This method is <em>lenient</em>: if there is no active Vert.x context (or if the
     * context-local slot has not been initialised), it returns {@link ContextSnapshot#empty()}.
     *
     * <p><b>Deep-copy semantics.</b> For each bound value whose runtime type has a registered
     * {@link ContextValueAdapter}, the adapter's {@link ContextValueAdapter#snapshot(Object)}
     * method is called to produce an immutable snapshot form; the frozen form is stored in the
     * snapshot map. {@link #bindSnapshot(ContextSnapshot)} then delegates to the same adapter's
     * {@link ContextValueAdapter#restoreFromSnapshot(Object)} to reconstruct a fresh live value,
     * keeping the snapshot independent of subsequent mutation in either the source or target
     * context. Values whose type has no registered adapter are stored by reference (correct for
     * truly immutable types). Feature modules (e.g. {@code vertique-logging}) contribute adapters
     * for their own mutable types via {@code META-INF/services}.
     *
     * @return an immutable snapshot of the current context bindings, or the empty snapshot
     */
    static ContextSnapshot snapshot() {
        if (CONTEXT_LOCAL == null) {
            return ContextSnapshot.empty();
        }
        Context ctx = Vertx.currentContext();
        if (ctx == null) {
            return ContextSnapshot.empty();
        }
        Map<String, Object> values = CONTEXT_LOCAL.get(ctx);
        if (values == null || values.isEmpty()) {
            return ContextSnapshot.empty();
        }
        Map<String, Object> snap = new HashMap<>(values.size());
        for (Map.Entry<String, Object> e : values.entrySet()) {
            @SuppressWarnings("rawtypes")
            ContextValueAdapter adapter = ContextLocalServiceProvider.adapterByFqcn(e.getKey());
            if (adapter != null && adapter.type().isInstance(e.getValue())) {
                // isInstance(adapter.type()) above proves the value is a ContextValue (T extends
                // ContextValue), so the cast required by the bounded adapter signature is safe.
                @SuppressWarnings("unchecked")
                Object frozen = adapter.snapshot((ContextValue) e.getValue());
                snap.put(e.getKey(), frozen);
            } else {
                snap.put(e.getKey(), e.getValue());
            }
        }
        return new ContextSnapshot(snap);
    }

    /**
     * Installs all entries from the given snapshot into the current Vert.x context, capturing
     * prior values for each key and returning a scope that restores them on close.
     *
     * <p>If the snapshot is empty, a no-op scope is returned and the method does not require a
     * duplicated context. Non-empty snapshots require a duplicated Vert.x context (same as all
     * other write operations).
     *
     * <p>For each entry whose FQCN key has a registered {@link ContextValueAdapter}, the adapter's
     * {@link ContextValueAdapter#restoreFromSnapshot(Object)} is called to materialise a fresh live
     * value before installation, keeping the rebound context independent of the snapshot. Entries
     * with no registered adapter are installed by reference.
     *
     * @param snapshot the snapshot to install; must not be {@code null}
     * @return a scope that restores prior values on close
     */
    static ContextHolder.Scope bindSnapshot(ContextSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.isEmpty()) {
            return NoOpScope.INSTANCE;
        }
        Context ctx = requireDuplicatedContextForWrite();
        Map<String, Object> snapshotValues = snapshot.values();
        Map<String, Object> toInstall = new HashMap<>(snapshotValues.size());
        for (Map.Entry<String, Object> e : snapshotValues.entrySet()) {
            @SuppressWarnings("rawtypes")
            ContextValueAdapter adapter = ContextLocalServiceProvider.adapterByFqcn(e.getKey());
            if (adapter != null) {
                Object materialised = adapter.restoreFromSnapshot(e.getValue());
                toInstall.put(e.getKey(), materialised);
            } else {
                toInstall.put(e.getKey(), e.getValue());
            }
        }
        return installScopedRaw(ctx, toInstall);
    }

    /**
     * Installs a pre-built FQCN-keyed map into the current context, recording prior values for
     * restoration on scope close. The caller is responsible for having already validated the
     * context. This is the shared low-level implementation used by both {@link #installScoped(Map)}
     * and {@link #bindSnapshot(ContextSnapshot)}.
     */
    private static ContextHolder.Scope installScopedRaw(Context ctx, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            requireContextValue(e.getKey(), e.getValue());
        }
        Map<String, Object> current = currentMap(ctx);
        String[] keys = new String[values.size()];
        boolean[] priorPresent = new boolean[values.size()];
        Object[] priorValues = new Object[values.size()];
        int i = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String key = e.getKey();
            keys[i] = key;
            priorPresent[i] = current.containsKey(key);
            priorValues[i] = priorPresent[i] ? current.get(key) : null;
            current.put(key, e.getValue());
            i++;
        }
        return new MultiKeyScope(ctx, keys, priorPresent, priorValues);
    }

    // --- Internal helpers ---

    /**
     * Validates that the value associated with the given key implements {@link ContextValue}.
     * Rejects {@code null} values because a present key with a null value is malformed — it is
     * never treated as a delete operation at this layer.
     *
     * @param key   the FQCN key under which the value would be stored; must not be {@code null}
     * @param value the candidate value; must not be {@code null} and must implement
     *              {@link ContextValue}
     * @throws IllegalArgumentException if {@code key} is {@code null}, or {@code value} is
     *                                  {@code null} or does not implement {@link ContextValue}
     */
    private static void requireContextValue(String key, Object value) {
        if (key == null) {
            // A null key is malformed erased input. Reject it in the pre-pass so the backing-map
            // write never NPEs mid-loop and leaves a partial install with no scope to unwind.
            throw new IllegalArgumentException("Cannot install a context value under a null key");
        }
        if (!(value instanceof ContextValue)) {
            throw new IllegalArgumentException("Cannot install non-ContextValue under key '" + key + "': "
                    + (value == null ? "null" : value.getClass().getName())
                    + " does not implement "
                    + ContextValue.class.getName());
        }
    }

    /**
     * Verifies that the current thread is on a duplicated Vert.x context. Holder writes are only
     * valid on a duplicated context so that concurrent dispatches do not stomp on one another's
     * bound values. Transport boundaries (event-bus consumers, Kafka, outbox relay, cron, delayed
     * jobs) are responsible for entering a duplicated context before invoking
     * {@link #bind(Class, Object)} or {@link #installScoped(Map)}.
     */
    private static Context requireDuplicatedContextForWrite() {
        Context ctx = Vertx.currentContext();
        if (ctx == null) {
            throw new IllegalStateException("Context propagation requires an active Vert.x context");
        }
        if (!(ctx instanceof ContextInternal internal) || !internal.isDuplicate()) {
            throw new IllegalStateException("Context propagation must be installed on a duplicated Vert.x context");
        }
        return ctx;
    }

    private static Object currentRaw(String key) {
        if (CONTEXT_LOCAL == null) {
            return null;
        }
        Context ctx = Vertx.currentContext();
        if (ctx == null) {
            return null;
        }
        Map<String, Object> values = CONTEXT_LOCAL.get(ctx);
        if (values == null) {
            return null;
        }
        return values.get(key);
    }

    private static Map<String, Object> currentMap(Context ctx) {
        // Atomic lazy-install via ContextLocal.get(ctx, supplier), which uses the CONCURRENT
        // access mode's VarHandle CAS loop under the hood (Vert.x 5.x ConcurrentAccessMode).
        // The previous get-then-put pattern had a non-atomic race where two concurrent first-
        // touches on the same duplicated context could each construct a fresh ConcurrentHashMap
        // and the second `put` would clobber the first — losing any binding the first installed.
        return CONTEXT_LOCAL.get(ctx, ConcurrentHashMap::new);
    }

    private static void restore(Context ctx, String key, boolean priorPresent, Object priorValue) {
        if (CONTEXT_LOCAL == null) {
            return;
        }
        Map<String, Object> values = CONTEXT_LOCAL.get(ctx);
        if (values == null) {
            return;
        }
        if (priorPresent) {
            values.put(key, priorValue);
        } else {
            values.remove(key);
        }
    }

    // --- Scope implementations ---

    /** No-op scope returned when there is nothing to install. */
    private enum NoOpScope implements ContextHolder.Scope {
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }

    /** Scope for a single {@link #bind(Class, Object)} install. Idempotent close. */
    private static final class SingleKeyScope implements ContextHolder.Scope {
        private final Context ctx;
        private final String key;
        private final boolean priorPresent;
        private final Object priorValue;
        private volatile boolean closed = false;

        SingleKeyScope(Context ctx, String key, boolean priorPresent, Object priorValue) {
            this.ctx = ctx;
            this.key = key;
            this.priorPresent = priorPresent;
            this.priorValue = priorValue;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            restore(ctx, key, priorPresent, priorValue);
        }
    }

    /**
     * Scope returned by {@link #installScoped(Map)}. Restores each key's prior value in reverse
     * order of installation on close.
     */
    private static final class MultiKeyScope implements ContextHolder.Scope {
        private final Context ctx;
        private final String[] keys;
        private final boolean[] priorPresent;
        private final Object[] priorValues;
        private volatile boolean closed = false;

        MultiKeyScope(Context ctx, String[] keys, boolean[] priorPresent, Object[] priorValues) {
            this.ctx = ctx;
            this.keys = keys;
            this.priorPresent = priorPresent;
            this.priorValues = priorValues;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (int i = keys.length - 1; i >= 0; i--) {
                restore(ctx, keys[i], priorPresent[i], priorValues[i]);
            }
        }
    }
}
