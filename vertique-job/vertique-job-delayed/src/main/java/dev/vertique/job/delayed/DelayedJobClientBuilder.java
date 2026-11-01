// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/**
 * Builder for a typed delayed-job client with a scope selection, obtained from
 * {@link DelayedJobClientFactory#client(Class)}.
 *
 * <p>Without a selection the client behaves like one from {@link DelayedJobClientFactory#create(Class)}
 * and every enqueue is stored in the {@code default} scope.
 *
 * @param <C> the contract interface the built client implements
 */
public interface DelayedJobClientBuilder<C> {

    /**
     * Stores every enqueue from the client in the given scope.
     *
     * <p>The scope replaces any {@link DelayedJob#scopeKey()} of a job the client enqueues, and it needs no
     * regeneration of an existing generated proxy.
     *
     * @param scope the scope key; must not be {@code null} and must be a valid
     *     {@link dev.vertique.core.scope.ScopeKey}
     * @return this builder
     * @throws IllegalArgumentException if {@code scope} is {@code null} or not a valid scope key; the
     *     message does not repeat the key
     */
    DelayedJobClientBuilder<C> scope(String scope);

    /**
     * Creates the client.
     *
     * @return a client implementing the contract interface - the generated proxy when present, else a
     *     JDK dynamic proxy; never {@code null}
     * @throws IllegalArgumentException if the contract fails validation
     */
    C create();
}
