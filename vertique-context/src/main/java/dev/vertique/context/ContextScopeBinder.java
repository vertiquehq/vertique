// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Internal framework binder that installs multiple context values into {@link ContextHolder} and
 * returns a single scope that unwinds all of them on close.
 *
 * <p><b>Internal framework API; not for consumer use.</b>
 *
 * <p>This binder is used by framework dispatchers (service invoker, Kafka consumer dispatcher,
 * outbox-service destination handler) to install multiple context values when entering a dispatch
 * scope. The returned scope closes each individual scope in reverse order (LIFO) when closed,
 * ensuring nested binds restore prior values correctly even when several types are bound at the
 * same level.
 *
 * <p>Implementation: each entry is bound through {@link ContextHolder#bind} individually, and the
 * returned composite scope tracks the per-entry scopes. If any individual {@code bind} throws,
 * scopes that were already installed are closed in reverse order before the exception propagates,
 * so callers never see a partially-bound state.
 *
 * <p>If {@code values} is empty, a no-op scope is returned.
 */
@Singleton
public final class ContextScopeBinder {

    private final ContextHolder holder;

    /**
     * Constructs a binder backed by the given {@link ContextHolder}.
     *
     * @param holder the context holder used for individual bindings
     */
    @Inject
    public ContextScopeBinder(ContextHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
    }

    /**
     * Binds all values from the given map into the current Vert.x context. Each entry is stored
     * under its key type's fully qualified class name. Returns a single {@link ContextHolder.Scope}
     * that restores all prior values when closed.
     *
     * <p>A pairing pre-pass validates every entry atomically before any binding is installed: each
     * value must be an instance of its declared key type. If any entry fails this check, an
     * {@link IllegalArgumentException} is thrown and no bindings are installed.
     *
     * <p>If a partial bind fails after the pre-pass (e.g., no active Vert.x context), the bindings
     * already installed by this call are closed in reverse order before the exception is propagated.
     *
     * <p>The returned scope's close is idempotent.
     *
     * @param values a map from {@link ContextValue} context type to value; must not be {@code null}
     * @return a scope that restores all prior bindings on close; never {@code null}
     * @throws NullPointerException     if {@code values} is {@code null} or any key/value is
     *                                  {@code null}
     * @throws IllegalArgumentException if any value is not an instance of its declared key type
     * @throws IllegalStateException    if called outside a Vert.x-associated context
     */
    public ContextHolder.Scope bindAll(Map<Class<? extends ContextValue>, ? extends ContextValue> values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            return NoOpScope.INSTANCE;
        }
        // Pairing pre-pass: validate all entries atomically before binding any.
        for (Map.Entry<Class<? extends ContextValue>, ? extends ContextValue> e : values.entrySet()) {
            requireNonNullType(e.getKey());
            if (!e.getKey().isInstance(e.getValue())) {
                throw new IllegalArgumentException("Cannot bind value "
                        + (e.getValue() == null
                                ? "null"
                                : e.getValue().getClass().getName())
                        + " under key " + e.getKey().getName()
                        + ": value is not an instance of the key type");
            }
        }
        ContextHolder.Scope[] scopes = new ContextHolder.Scope[values.size()];
        int i = 0;
        try {
            for (Map.Entry<Class<? extends ContextValue>, ? extends ContextValue> entry : values.entrySet()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                ContextHolder.Scope scope = holder.bind((Class) entry.getKey(), entry.getValue());
                scopes[i++] = scope;
            }
        } catch (RuntimeException | Error t) {
            // Unwind any scopes already installed by this call before propagating.
            for (int j = i - 1; j >= 0; j--) {
                try {
                    scopes[j].close();
                } catch (RuntimeException closeErr) {
                    // Swallow close failures to preserve the original exception; rebind-state
                    // is already unrecoverable at this point.
                    t.addSuppressed(closeErr);
                }
            }
            throw t;
        }
        return new CompositeScope(scopes);
    }

    /**
     * Authoritative bind: installs every entry in {@code bindings} AND clears every type in
     * {@code clearTypes}, capturing the prior value at each affected key so {@link
     * ContextHolder.Scope#close()} restores the holder to its pre-call state — including
     * re-installing values that were cleared by this call.
     *
     * <p>Used by {@link DurableContextPropagator#bindFrom} so a durable boundary's restoration is
     * authoritative: every registered durable type absent from the metadata is cleared for the
     * scope's lifetime, preventing ambient context from leaking into a subsequent
     * {@code mergeCaptured(...)} call inside the scope. The composite-bind APIs without this
     * authoritative behavior leave non-overlapping ambient values in place.
     *
     * <p>Types that appear in both {@code bindings} and {@code clearTypes} are treated as
     * bindings — the value wins. {@code clearTypes} may name types that are not currently bound;
     * those are recorded as "was-absent" so close is still a clean no-op for them.
     *
     * <p>If both arguments are empty, a no-op scope is returned and the duplicated-context guard
     * is not exercised.
     *
     * @param bindings   values to install, keyed by context type
     * @param clearTypes types to clear from the holder for the scope's lifetime
     * @return a scope that restores the pre-call state on close
     * @throws IllegalStateException if called outside a duplicated Vert.x context with non-empty
     *                               work to perform
     */
    public ContextHolder.Scope bindAllAuthoritative(Map<Class<?>, Object> bindings, Set<Class<?>> clearTypes) {
        Objects.requireNonNull(bindings, "bindings must not be null");
        Objects.requireNonNull(clearTypes, "clearTypes must not be null");
        if (bindings.isEmpty() && clearTypes.isEmpty()) {
            return NoOpScope.INSTANCE;
        }
        Map<String, Object> fqcnBindings = new HashMap<>(bindings.size());
        for (Map.Entry<Class<?>, Object> e : bindings.entrySet()) {
            requireNonNullType(e.getKey());
            fqcnBindings.put(e.getKey().getName(), e.getValue());
        }
        Set<String> fqcnClear = new HashSet<>(clearTypes.size());
        for (Class<?> type : clearTypes) {
            requireNonNullType(type);
            fqcnClear.add(type.getName());
        }
        return DefaultContextHolder.installScopedAuthoritative(fqcnBindings, fqcnClear);
    }

    /**
     * Rejects a {@code null} context-type key before it is dereferenced (e.g. via {@code getName()}
     * or {@code isInstance(...)}). A {@code null} key is malformed input; failing fast here with a
     * descriptive {@link IllegalArgumentException} keeps the composite binders consistent with the
     * holder's erased-path guard rather than surfacing a bare {@link NullPointerException}.
     *
     * @param type the context-type key to validate
     * @throws IllegalArgumentException if {@code type} is {@code null}
     */
    private static void requireNonNullType(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("context type key must not be null");
        }
    }

    // --- Scope implementations ---

    /** A no-op scope returned when no values are bound. Close is always a no-op. */
    private enum NoOpScope implements ContextHolder.Scope {
        /** The singleton no-op scope instance. */
        INSTANCE;

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * A composite scope that closes its constituent scopes in reverse order (LIFO) on close.
     * Idempotent: subsequent close calls after the first are no-ops.
     */
    private static final class CompositeScope implements ContextHolder.Scope {

        private final ContextHolder.Scope[] scopes;
        private volatile boolean closed = false;

        CompositeScope(ContextHolder.Scope[] scopes) {
            // Defensive copy to ensure the array is owned by this scope.
            this.scopes = scopes.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (int i = scopes.length - 1; i >= 0; i--) {
                scopes[i].close();
            }
        }
    }
}
