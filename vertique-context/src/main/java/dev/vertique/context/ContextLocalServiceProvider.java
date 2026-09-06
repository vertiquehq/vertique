// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.ContextValueAdapter;
import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Vert.x SPI provider that registers the single shared {@link ContextLocal} slot for the
 * context-propagation substrate.
 *
 * <p>Loaded automatically via {@code META-INF/services/io.vertx.core.spi.VertxServiceProvider}
 * during {@link io.vertx.core.Vertx#vertx()} bootstrap — before any contexts are created.
 * Registers a {@link ContextLocal}{@code <Map<String, Object>>} and injects it into
 * {@link DefaultContextHolder} via {@link DefaultContextHolder#initContextLocalIfAbsent}.
 *
 * <p><b>Copy duplicator:</b> the slot is registered with a deep-copy function rather than
 * Vert.x's default identity duplicator. When a caller invokes
 * {@code ContextInternal.duplicate(true)} on a context that already has the holder map populated,
 * the duplicated context receives a fresh {@link ConcurrentHashMap} (matching the map type
 * {@link DefaultContextHolder} uses for the live holder, so concurrent mutators on the duplicate
 * keep the same visibility/atomicity guarantees). Deep-copy behavior per value type is delegated
 * to {@link ContextValueAdapter} SPI implementations discovered via Java
 * {@link java.util.ServiceLoader}. Feature modules ship adapters for their own mutable types
 * (each adapter must produce a fresh independent live value from a snapshot so mutations on one
 * context do not bleed into the other). Values with no registered adapter are stored by reference
 * (correct for immutable values; also the intended semantic for mutable containers that model a
 * single logical execution and must be shared across duplicated contexts).
 *
 * <p>The default {@code duplicate(false)} path (the Vert.x lifecycle for routing-context
 * duplication, event-bus dispatch, etc.) gives the duplicate a {@code null} local regardless of
 * the duplicator — that path is unchanged. The substrate's existing per-key write semantics
 * (snapshot/restore via {@link DefaultContextHolder.SingleKeyScope}) compose with the new copy
 * duplicator: the copy ensures inter-context isolation, the scope mechanism ensures intra-context
 * invariants.
 *
 * <p>The static adapter maps ({@link #ADAPTERS_BY_TYPE} and {@link #ADAPTERS_BY_FQCN}) are
 * populated once during class initialisation from the ServiceLoader. They are available to
 * {@link DefaultContextHolder} via the package-private accessors {@link #adapterFor(Class)} and
 * {@link #adapterByFqcn(String)}. Because {@code ContextLocalServiceProvider} is loaded by the
 * Vert.x bootstrap ({@code VertxServiceProvider} SPI) before any user code accesses
 * {@code DefaultContextHolder}, the adapters are always available when
 * {@link DefaultContextHolder#snapshot()} or {@link DefaultContextHolder#bindSnapshot(ContextSnapshot)}
 * first runs.
 *
 * <p>This is the only provider for the dispatch-context map. The legacy services-side
 * {@code DispatchContextServiceProvider} has been removed; {@code DispatchContext.current(...)}
 * now delegates directly to {@link DefaultContextHolder#currentValue}.
 *
 * <p><b>Vert.x version compatibility:</b> {@code VertxBootstrap} lives in
 * {@code io.vertx.core.internal}, which is not part of Vert.x's stable public API. The framework
 * pins Vert.x via the project BOM; on a major Vert.x upgrade this import may need to move to
 * the renamed bootstrap class.
 */
public class ContextLocalServiceProvider implements VertxServiceProvider {

    // --- Adapter maps (loaded once at class initialisation) ---

    /**
     * Adapter map keyed by value runtime type. Populated from ServiceLoader at class-init time;
     * immutable after construction.
     */
    private static final Map<Class<?>, ContextValueAdapter<?>> ADAPTERS_BY_TYPE = loadAdapters();

    /**
     * Adapter map keyed by the type's FQCN string, for use in the {@link DefaultContextHolder}
     * snapshot/restore paths that iterate over the FQCN-keyed holder map.
     * Derived from {@link #ADAPTERS_BY_TYPE}; immutable after construction.
     */
    private static final Map<String, ContextValueAdapter<?>> ADAPTERS_BY_FQCN = loadAdaptersByFqcn();

    // --- VertxServiceProvider ---

    /**
     * Registers the context-local map slot and injects it into {@link DefaultContextHolder}.
     *
     * <p>Guards against double registration: when multiple Vert.x instances are created in the
     * same JVM (e.g. integration-test suites that boot two separate {@code Vertx} instances —
     * possibly concurrently when parallel test classes each boot their own), this SPI is invoked
     * once per {@code Vertx.vertx()} call. Each call to {@link ContextLocal#registerLocal}
     * allocates a new JVM-global slot with an incremented key index. If
     * {@link DefaultContextHolder#CONTEXT_LOCAL} were overwritten with the new slot on every call,
     * contexts from the <em>first</em> Vert.x instance would receive {@code Invalid key index}
     * errors because their {@code locals[]} array was sized for the original slot count, not the
     * enlarged one. Registration is delegated to
     * {@link DefaultContextHolder#initContextLocalIfAbsent} as a {@code Supplier}, so the
     * {@code registerLocal()} call is performed at most once and only by the thread that wins the
     * registration race — concurrent callers never allocate a second slot.
     *
     * @param bootstrap the Vert.x bootstrap instance
     */
    @Override
    public void init(VertxBootstrap bootstrap) {
        DefaultContextHolder.initContextLocalIfAbsent(ContextLocalServiceProvider::registerMapLocal);
    }

    // --- Package-private adapter accessors (shared with DefaultContextHolder) ---

    /**
     * Returns the adapter registered for the given value runtime type, or {@code null} if none is
     * registered.
     *
     * @param type the value type to look up; must not be {@code null}
     * @return the registered {@link ContextValueAdapter}, or {@code null}
     */
    static ContextValueAdapter<?> adapterFor(Class<?> type) {
        return ADAPTERS_BY_TYPE.get(type);
    }

    /**
     * Returns the adapter registered for the given FQCN string, or {@code null} if none is
     * registered. Used by {@link DefaultContextHolder} snapshot/restore paths that iterate over
     * the FQCN-keyed holder map.
     *
     * @param fqcn the fully-qualified class name key; must not be {@code null}
     * @return the registered {@link ContextValueAdapter}, or {@code null}
     */
    static ContextValueAdapter<?> adapterByFqcn(String fqcn) {
        return ADAPTERS_BY_FQCN.get(fqcn);
    }

    // --- Private helpers ---

    /**
     * Registers a {@link ContextLocal} for a {@code Map<String, Object>} with a deep-copy
     * duplicator. The duplicator is invoked by Vert.x only on {@code duplicate(true)}; on
     * {@code duplicate(false)} (the default Vert.x lifecycle path) the duplicated context's local
     * is {@code null} and the duplicator is not consulted.
     *
     * <p>The double-cast through the raw type is required because {@code Map.class} is
     * {@code Class<Map>} (raw) and Java does not allow a direct assignment from
     * {@code ContextLocal<Map>} to {@code ContextLocal<Map<String, Object>>}.
     *
     * @return the registered context-local slot
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ContextLocal<Map<String, Object>> registerMapLocal() {
        return (ContextLocal<Map<String, Object>>) (ContextLocal) ContextLocal.registerLocal(Map.class, src -> {
            Map<String, Object> source = (Map<String, Object>) src;
            // Use ConcurrentHashMap so the duplicated map matches DefaultContextHolder's live
            // holder type and keeps the same concurrent-mutator visibility/atomicity guarantees.
            Map<String, Object> copy = new ConcurrentHashMap<>(source.size());
            source.forEach((key, value) -> copy.put(key, deepCopyValue(value)));
            return copy;
        });
    }

    /**
     * Returns a deep copy of the value when a registered {@link ContextValueAdapter} matches the
     * value's runtime type; otherwise returns the original reference. Adapters are discovered via
     * Java {@link java.util.ServiceLoader} once at class initialisation so {@code duplicate(true)}
     * stays allocation-cheap for values with no adapter.
     *
     * @param value the holder map value to copy; may be {@code null}
     * @return a deep copy produced by the matching adapter, or the original reference if no
     *         adapter is registered for the value's type; {@code null} in, {@code null} out
     */
    private static Object deepCopyValue(Object value) {
        if (value == null) return null;
        @SuppressWarnings("rawtypes")
        ContextValueAdapter adapter = ADAPTERS_BY_TYPE.get(value.getClass());
        if (adapter == null) return value;
        // An adapter is registered for value.getClass() (its T extends ContextValue), so the value
        // is a ContextValue; the cast required by the bounded adapter signature is safe. This is the
        // FR-CTX-206 duplicator path: it re-copies values that already passed a write-path check.
        @SuppressWarnings("unchecked")
        Object duplicated = adapter.duplicate((ContextValue) value);
        return duplicated;
    }

    /**
     * Loads all {@link ContextValueAdapter} SPI implementations from the ServiceLoader, keyed by
     * the value type they handle. Throws {@link IllegalStateException} if two adapters are
     * registered for the same type — this is a configuration error detectable at bootstrap.
     *
     * @return immutable map from value type to its adapter
     */
    @SuppressWarnings("rawtypes")
    private static Map<Class<?>, ContextValueAdapter<?>> loadAdapters() {
        Map<Class<?>, ContextValueAdapter<?>> map = new HashMap<>();
        for (ContextValueAdapter adapter : java.util.ServiceLoader.load(ContextValueAdapter.class)) {
            Class<?> type = adapter.type();
            ContextValueAdapter<?> prior = map.put(type, adapter);
            if (prior != null) {
                // Two ServiceLoader entries for the same type — substrate-level configuration error.
                throw new IllegalStateException("Duplicate ContextValueAdapter for type " + type.getName() + ": "
                        + prior.getClass().getName() + " and "
                        + adapter.getClass().getName());
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Builds the FQCN-keyed map from the already-loaded {@link #ADAPTERS_BY_TYPE}.
     *
     * @return immutable map from FQCN string to its adapter
     */
    private static Map<String, ContextValueAdapter<?>> loadAdaptersByFqcn() {
        Map<String, ContextValueAdapter<?>> byFqcn = new HashMap<>(ADAPTERS_BY_TYPE.size());
        for (ContextValueAdapter<?> adapter : ADAPTERS_BY_TYPE.values()) {
            byFqcn.put(adapter.type().getName(), adapter);
        }
        return Map.copyOf(byFqcn);
    }
}
