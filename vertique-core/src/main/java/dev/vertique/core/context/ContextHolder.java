// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Optional;

/**
 * Request-scoped holder for typed context values, backed by Vert.x context-local storage.
 *
 * <p>Each value is stored under its type's fully qualified class name. Bindings are scoped: calling
 * {@link #bind(Class, Object)} returns a {@link Scope} that restores the previous binding (or
 * removes the key if previously absent) when closed. Scopes are designed for try-with-resources.
 *
 * <p>Semantics are tied to the current Vert.x {@code Context}, not to the Java carrier thread.
 * This ensures correct behavior under virtual-thread scheduling where the executing thread may
 * change while the Vert.x context remains stable.
 *
 * <p>Outside a Vert.x-associated context:
 * <ul>
 *   <li>{@link #current(Class)} returns {@link Optional#empty()}.
 *   <li>{@link #bind(Class, Object)} throws {@link IllegalStateException}.
 * </ul>
 */
public interface ContextHolder {

    /**
     * Returns the currently bound value of the given type, or empty if not bound or outside a
     * Vert.x context.
     *
     * @param type the context value type; must not be {@code null}
     * @param <T>  the context type
     * @return the currently bound value, or {@link Optional#empty()}
     */
    <T> Optional<T> current(Class<T> type);

    /**
     * Binds the given value under the given type for the current Vert.x context scope. Returns a
     * {@link Scope} that restores the previous binding when closed.
     *
     * @param type  the context value type; must not be {@code null}
     * @param value the value to bind; must not be {@code null}
     * @param <T>   the context type; must implement {@link ContextValue}
     * @return a scope that restores the prior binding on close
     * @throws NullPointerException     if {@code type} or {@code value} is {@code null}
     * @throws IllegalStateException    if called outside a Vert.x-associated context
     */
    <T extends ContextValue> Scope bind(Class<T> type, T value);

    /**
     * A closeable scope that restores a prior context binding when closed.
     *
     * <p>Closing a scope is idempotent: subsequent calls after the first are no-ops.
     * Scopes should be used with try-with-resources to ensure correct LIFO unwinding.
     */
    interface Scope extends AutoCloseable {

        /**
         * Restores the prior context binding. Idempotent: subsequent calls after the first are
         * no-ops.
         */
        @Override
        void close();
    }
}
