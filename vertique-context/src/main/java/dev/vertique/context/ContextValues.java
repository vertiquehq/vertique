// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Public static facade over {@link DefaultContextHolder} for reading and writing typed values in
 * the current Vert.x request context.
 *
 * <p><b>Read methods ({@link #current}, {@link #snapshot}) are lenient</b>: they return
 * {@link Optional#empty()} or {@link ContextSnapshot#empty()} when called outside a Vert.x
 * context, rather than throwing.
 *
 * <p><b>Write methods ({@link #bind}, {@link #mutate}, {@link #mutateIfPresent}, {@link #remove},
 * and {@link #bindSnapshot} with a non-empty snapshot) are fail-fast</b>: they delegate to
 * package-private helpers in {@link DefaultContextHolder} that enforce the duplicated-context
 * invariant via {@code requireDuplicatedContextForWrite()}. This is the single enforcement
 * point — no new guard is added here.
 *
 * <p>Values are keyed by their type's fully qualified class name, matching the storage layout
 * used by the underlying {@link ContextHolder}.
 *
 * <p>This class is not instantiable.
 */
public final class ContextValues {

    private ContextValues() {}

    // --- Read operations (lenient) ---

    /**
     * Returns the currently bound value of the given type, or empty if not bound or if called
     * outside a Vert.x context.
     *
     * @param type the context value type; must not be {@code null}
     * @param <T>  the value type
     * @return the currently bound value, or {@link Optional#empty()}
     */
    public static <T> Optional<T> current(Class<T> type) {
        return DefaultContextHolder.currentValue(type);
    }

    /**
     * Captures an immutable snapshot of all values currently bound in the active Vert.x context.
     * Returns {@link ContextSnapshot#empty()} if called outside a Vert.x context or when no values
     * are bound. See {@link DefaultContextHolder#snapshot()} for the deep-copy contract applied
     * via {@link dev.vertique.core.context.ContextValueAdapter} to mutable framework values.
     *
     * @return an immutable snapshot of the current context bindings, or the empty snapshot
     */
    public static ContextSnapshot snapshot() {
        return DefaultContextHolder.snapshot();
    }

    // --- Write operations (fail-fast on non-duplicated context) ---

    /**
     * Binds the given value under the given type for the current Vert.x duplicated context.
     * Returns a {@link ContextHolder.Scope} that restores the previous binding (or removes the key
     * if previously absent) when closed.
     *
     * @param type  the context value type; must not be {@code null}
     * @param value the value to bind; must not be {@code null}
     * @param <T>   the value type; must implement {@link ContextValue}
     * @return a scope that restores the prior binding on close
     * @throws IllegalStateException if called outside a Vert.x context or on a non-duplicated
     *                               context
     */
    public static <T extends ContextValue> ContextHolder.Scope bind(Class<T> type, T value) {
        return DefaultContextHolder.bindStatic(type, value);
    }

    /**
     * Gets or initialises the value bound under {@code type} in the current Vert.x context and
     * applies the given consumer mutation. If no value of the correct type is bound,
     * {@code initialValue.get()} is called and the result is installed before the mutation is
     * applied.
     *
     * @param type         the context value type; must not be {@code null}
     * @param initialValue supplier for the initial value when none is bound; must not be
     *                     {@code null}
     * @param mutation     consumer applied to the (possibly newly initialised) value; must not be
     *                     {@code null}
     * @param <T>          the value type; must implement {@link ContextValue}
     * @throws IllegalStateException if called outside a Vert.x context or on a non-duplicated
     *                               context
     */
    public static <T extends ContextValue> void mutate(
            Class<T> type, Supplier<? extends T> initialValue, Consumer<? super T> mutation) {
        DefaultContextHolder.mutate(type, initialValue, mutation);
    }

    /**
     * Applies the given consumer mutation to the currently-bound value of {@code type} if a value
     * of that type is bound. No-ops if the key is absent or holds a value of the wrong type.
     *
     * @param type     the context value type; must not be {@code null}
     * @param mutation consumer applied to the existing value; must not be {@code null}
     * @param <T>      the value type; must implement {@link ContextValue}
     * @throws IllegalStateException if called outside a Vert.x context or on a non-duplicated
     *                               context
     */
    public static <T extends ContextValue> void mutateIfPresent(Class<T> type, Consumer<? super T> mutation) {
        DefaultContextHolder.mutateIfPresent(type, mutation);
    }

    /**
     * Removes the binding for the given type from the current Vert.x context's value map.
     *
     * @param type the context value type whose binding should be removed; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x context or on a non-duplicated
     *                               context
     */
    public static void remove(Class<?> type) {
        DefaultContextHolder.removeKey(type);
    }

    /**
     * Installs all entries from the given snapshot into the current Vert.x context, capturing
     * prior values for each key and returning a scope that restores them on close. If the snapshot
     * is empty, a no-op scope is returned and no duplicated-context check is performed.
     *
     * @param snapshot the snapshot to install; must not be {@code null}
     * @return a scope that restores prior values on close; never {@code null}
     * @throws IllegalStateException if the snapshot is non-empty and called outside a Vert.x
     *                               context or on a non-duplicated context
     */
    public static ContextHolder.Scope bindSnapshot(ContextSnapshot snapshot) {
        return DefaultContextHolder.bindSnapshot(snapshot);
    }
}
