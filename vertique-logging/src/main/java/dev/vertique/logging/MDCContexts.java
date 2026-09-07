// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import dev.vertique.context.ContextValues;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public static facade for MDC-style (Mapped Diagnostic Context) diagnostic values stored in the
 * current Vert.x request context.
 *
 * <p>MDC values are held in a single {@link MDCContext} instance bound under the
 * {@code MDCContext.class.getName()} key in the active {@link ContextHolder} slot.
 *
 * <p><b>Fail-fast writes</b>: {@link #put}, {@link #putAll}, {@link #remove}, {@link #removeAll},
 * {@link #clear}, and {@link #bindAll} (when given a non-empty map) all require a Vert.x
 * duplicated context and throw {@link IllegalStateException} if called outside one. This matches
 * the invariant enforced by {@link DefaultContextHolder#requireDuplicatedContextForWrite()}.
 *
 * <p><b>Lenient reads</b>: {@link #get} and {@link #copy} return {@code null} / empty map outside
 * a Vert.x context rather than throwing.
 *
 * <p>{@link #bindAll} snapshots only the MDC keys it touches (capturing their prior presence and
 * value) and restores them in reverse order on scope close. Keys outside the bound set are
 * unaffected by close. This is implemented via the private {@code MdcKeyScope} inner class rather
 * than reusing {@code DefaultContextHolder}'s {@code MultiKeyScope}, because MDC entries are
 * sub-keys inside a single holder value, not separate holder keys.
 *
 * <p>This class is not instantiable.
 */
public final class MDCContexts {

    private MDCContexts() {}

    // --- Service-dispatch wiring (public factories) ---

    /**
     * Returns a {@link ServiceDispatchContextEncoder} that captures the ambient holder-bound MDC
     * state as an immutable {@link DiagnosticContextSnapshot} on the wire. Use this in Dagger
     * {@code @Provides @IntoSet} bindings or in tests that need to wire MDC propagation manually.
     *
     * <p>The encoder is keyed by {@code MDCContext.class.getName()} so receive-side decoding picks
     * up the same slot.
     *
     * @return a freshly constructed encoder; safe to share across captures
     */
    public static ServiceDispatchContextEncoder<?> serviceDispatchEncoder() {
        return ServiceDispatchCodecs.snapshotEncoder(
                MDCContext.class, live -> new DiagnosticContextSnapshot(live.copy()));
    }

    /**
     * Returns a {@link ServiceDispatchContextDecoder} that materialises a fresh {@link MDCContext}
     * from a wire-format {@link DiagnosticContextSnapshot}. Pairs with
     * {@link #serviceDispatchEncoder()}.
     *
     * @return a freshly constructed decoder; safe to share across decodes
     */
    public static ServiceDispatchContextDecoder<?> serviceDispatchDecoder() {
        return ServiceDispatchCodecs.snapshotDecoder(
                MDCContext.class, DiagnosticContextSnapshot.class, MDCContext::fromSnapshot);
    }

    // --- Write operations (fail-fast) ---

    /**
     * Associates the given key with the given value in the current request's MDC. Creates an
     * {@link MDCContext} on first call within the current duplicated context.
     *
     * @param key   the MDC key; must not be {@code null}
     * @param value the MDC value; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void put(String key, String value) {
        ContextValues.mutate(MDCContext.class, MDCContext::new, mdc -> mdc.put(key, value));
    }

    /**
     * Copies all entries from the given map into the current request's MDC. Creates an
     * {@link MDCContext} on first call within the current duplicated context.
     *
     * @param values the entries to add; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void putAll(Map<String, String> values) {
        ContextValues.mutate(MDCContext.class, MDCContext::new, mdc -> mdc.putAll(values));
    }

    /**
     * Removes the entry for the given key from the current request's MDC. No-ops if no
     * {@link MDCContext} is currently bound.
     *
     * @param key the MDC key to remove; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void remove(String key) {
        ContextValues.mutateIfPresent(MDCContext.class, mdc -> mdc.remove(key));
    }

    /**
     * Removes the entries for all given keys from the current request's MDC. No-ops if no
     * {@link MDCContext} is currently bound.
     *
     * @param keys the MDC keys to remove; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void removeAll(Collection<String> keys) {
        ContextValues.mutateIfPresent(MDCContext.class, mdc -> keys.forEach(mdc::remove));
    }

    /**
     * Removes all entries from the current request's MDC and drops the {@link MDCContext} binding
     * entirely. No-ops if no {@link MDCContext} is currently bound.
     *
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void clear() {
        ContextValues.remove(MDCContext.class);
    }

    // --- Read operations (lenient) ---

    /**
     * Returns the value bound to the given key in the current request's MDC, or {@code null} if
     * the key is absent or no MDC is bound. Lenient: returns {@code null} outside a Vert.x
     * context.
     *
     * @param key the MDC key; must not be {@code null}
     * @return the current value, or {@code null}
     */
    public static String get(String key) {
        return ContextValues.current(MDCContext.class).map(mdc -> mdc.get(key)).orElse(null);
    }

    /**
     * Returns an immutable copy of all current MDC entries, or an empty map if no MDC is bound.
     * Lenient: returns an empty map outside a Vert.x context.
     *
     * @return immutable copy of the current MDC entries; never {@code null}
     */
    public static Map<String, String> copy() {
        return ContextValues.current(MDCContext.class).map(MDCContext::copy).orElse(Map.of());
    }

    // --- Caller-override factory for non-duplicated-context dispatch sites ---

    /**
     * INTERNAL framework seam — used by the framework's dispatch sites (cron, delayed jobs,
     * correlation and REST ingress); not an application contract and outside the maturity promise.
     *
     * <p>Returns the FQCN key under which the MDC holder value lives in the unified
     * dispatch-context map. Use this when composing a caller-override entry for
     * {@link DispatchEnvelopeBuilder#build} from a non-duplicated Vert.x context (e.g. cron or
     * delayed-job scheduler threads), where direct holder writes are not permitted.
     *
     * @return the canonical dispatch-context key for the MDC holder value
     */
    public static String holderKey() {
        return MDCContext.class.getName();
    }

    /**
     * INTERNAL framework seam — used by the framework's dispatch sites (cron, delayed jobs,
     * correlation and REST ingress); not an application contract and outside the maturity promise.
     *
     * <p>Returns a wire-format MDC value initialised from the given entries, suitable for use as a
     * caller-override entry in {@link DispatchEnvelopeBuilder#build}. The value is a
     * {@link DiagnosticContextSnapshot} — the same form the {@link #serviceDispatchEncoder()
     * built-in MDC service-dispatch encoder} produces from ambient MDC — so caller-supplied
     * values and encoder-captured values share one decoder path:
     * <pre>{@code
     * if (!mdc.isEmpty()) {
     *     overrides.put(MDCContexts.holderKey(), MDCContexts.holderValue(mdc));
     * }
     * }</pre>
     * The receiving {@code ServiceMethodInvoker} feeds this snapshot through
     * {@link #serviceDispatchDecoder()}, which materialises a fresh {@code MDCContext} and
     * installs it via {@link InboundDispatchScope}.
     *
     * @param entries the MDC entries to seed the snapshot with; must not be {@code null}
     * @return a {@link DiagnosticContextSnapshot} carrying an immutable copy of the entries
     */
    public static Object holderValue(Map<String, String> entries) {
        return new DiagnosticContextSnapshot(entries);
    }

    // --- Scoped bind ---

    /**
     * Installs the given entries into the current request's MDC for the lifetime of the returned
     * scope. On close, only the keys touched by this call are restored to their prior values
     * (including absence). Keys outside this set that were mutated after binding are preserved.
     *
     * <p>If {@code entries} is {@code null} or empty, a no-op scope is returned and no
     * duplicated-context check is performed.
     *
     * <p>The scope's close is idempotent and restores prior key states in reverse install order.
     *
     * @param entries the MDC entries to bind; may be {@code null} or empty
     * @return a scope that restores the prior MDC state for the bound keys on close; never
     *         {@code null}
     * @throws IllegalStateException if {@code entries} is non-empty and called outside a Vert.x
     *                               duplicated context
     */
    public static ContextHolder.Scope bindAll(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return NoOpMdcScope.INSTANCE;
        }
        // Snapshot prior values and apply new entries inside a single mutate call so the
        // MDCContext is created on demand if not yet bound.
        String[] keys = entries.keySet().toArray(new String[0]);
        boolean[] priorPresent = new boolean[keys.length];
        String[] priorValues = new String[keys.length];

        ContextValues.mutate(MDCContext.class, MDCContext::new, mdc -> {
            for (int i = 0; i < keys.length; i++) {
                String prior = mdc.get(keys[i]);
                priorPresent[i] = prior != null;
                priorValues[i] = prior;
            }
            mdc.putAll(entries);
        });

        // Capture the install-time Vert.x context. ContextValues.mutate above already verified
        // (via requireDuplicatedContextForWrite) that we are on a duplicated context, so this is
        // never null here. Close must restore against THIS context, not whatever happens to be
        // current at close time — see DefaultContextHolder.MultiKeyScope for the same contract.
        Context installCtx = Vertx.currentContext();
        return new MdcKeyScope(installCtx, keys, priorPresent, priorValues);
    }

    /**
     * INTERNAL framework seam — used by the framework's dispatch sites (cron, delayed jobs,
     * correlation and REST ingress); not an application contract and outside the maturity promise.
     *
     * <p>Snapshots the current value (or absence) of every key in {@code keys} and returns a
     * {@link ContextHolder.Scope} whose {@link ContextHolder.Scope#close()} restores each key to
     * its snapshotted state — independent of any {@link #put}, {@link #putAll}, {@link #remove},
     * or {@link #removeAll} calls made between snapshot and close. Keys outside {@code keys} are
     * untouched by close.
     *
     * <p>Used by correlation ingress to guarantee that the framework-mirrored MDC keys
     * ({@code requestId}, {@code correlationId}, {@code causationId}, {@code traceId},
     * {@code spanId}) are restored at request end — including keys that were absent at ingress
     * but added later by enricher mutations.
     *
     * <p>If {@code keys} is {@code null} or empty, a no-op scope is returned and the duplicated-
     * context guard is not exercised. For a non-empty set, snapshotting reads the current
     * {@link MDCContext} via the lenient {@code copy()} accessor (returns an empty map outside
     * a Vert.x context). Restoration on close uses
     * {@link DefaultContextHolder#mutateIfPresentOnContext} so it no-ops cleanly when the holder
     * binding has been cleared in the meantime.
     *
     * @param keys the MDC keys to snapshot; may be {@code null} or empty
     * @return a scope that restores each snapshotted key on close; never {@code null}
     */
    public static ContextHolder.Scope snapshotKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return NoOpMdcScope.INSTANCE;
        }
        String[] keyArr = keys.toArray(new String[0]);
        boolean[] priorPresent = new boolean[keyArr.length];
        String[] priorValues = new String[keyArr.length];

        // Snapshot via lenient read — empty MDCContext (or none) yields all-absent prior state.
        Map<String, String> currentEntries = copy();
        for (int i = 0; i < keyArr.length; i++) {
            String prior = currentEntries.get(keyArr[i]);
            priorPresent[i] = prior != null;
            priorValues[i] = prior;
        }

        // Bind against the install-time context so restoration runs against the same context
        // even if close() fires from a different context. May be null when invoked outside a
        // Vert.x context — restoration becomes a no-op via mutateIfPresentOnContext.
        Context installCtx = Vertx.currentContext();
        return new MdcKeyScope(installCtx, keyArr, priorPresent, priorValues);
    }

    // --- Scope implementations ---

    /** No-op scope returned for an empty or null {@code entries} map in {@link #bindAll}. */
    private enum NoOpMdcScope implements ContextHolder.Scope {
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * Scope returned by {@link #bindAll}. Restores each snapshotted MDC key's prior value (or
     * absence) in reverse order of installation on close. Idempotent.
     *
     * <p>The scope binds against the Vert.x {@link Context} that was current at install time
     * (captured as {@code installCtx}). On close, restoration runs against {@code installCtx} —
     * not whatever happens to be {@link Vertx#currentContext()} at close time — so a scope
     * closed on a different context (or none) still restores the binding it opened, matching
     * the contract that {@link DefaultContextHolder.MultiKeyScope} follows for typed values.
     */
    private static final class MdcKeyScope implements ContextHolder.Scope {

        private final Context installCtx;
        private final String[] keys;
        private final boolean[] priorPresent;
        private final String[] priorValues;
        private volatile boolean closed = false;

        MdcKeyScope(Context installCtx, String[] keys, boolean[] priorPresent, String[] priorValues) {
            this.installCtx = installCtx;
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
            // Restore in reverse install order against the captured install-time context; no-op
            // if the MDCContext was cleared in the meantime (mutateIfPresentOnContext handles
            // the absent-binding case).
            List<String> absent = new ArrayList<>();
            DefaultContextHolder.mutateIfPresentOnContext(installCtx, MDCContext.class, mdc -> {
                for (int i = keys.length - 1; i >= 0; i--) {
                    if (priorPresent[i]) {
                        mdc.put(keys[i], priorValues[i]);
                    } else {
                        absent.add(keys[i]);
                    }
                }
                absent.forEach(mdc::remove);
            });
        }
    }
}
