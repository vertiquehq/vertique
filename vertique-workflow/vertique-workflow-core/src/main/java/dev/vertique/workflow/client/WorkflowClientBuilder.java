// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

/**
 * Builder for a typed workflow client with a scope or version selection, obtained from
 * {@link WorkflowClientFactory#client(Class)} or {@link WorkflowClientFactory#client(Class, String)}.
 *
 * <p>Without a selection the client starts the current version of its definition and lets the engine
 * resolve the scopes.
 *
 * @param <C> the contract interface the built client implements
 */
public interface WorkflowClientBuilder<C> {

    /**
     * Starts the client's instances in the given scope.
     *
     * @param startScope the scope key; must not be {@code null}
     * @return this builder
     */
    WorkflowClientBuilder<C> scope(String startScope);

    /**
     * Pins the client's starts to an explicit definition version.
     *
     * @param version the version to start; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code version} is not positive
     */
    WorkflowClientBuilder<C> version(long version);

    /**
     * Pins the client's starts to an explicit definition version in the given scope.
     *
     * @param targetScope the scope key whose definition version is started; must not be {@code null}
     * @param version the version to start; must be positive
     * @return this builder
     * @throws IllegalArgumentException if {@code version} is not positive
     */
    WorkflowClientBuilder<C> version(String targetScope, long version);

    /**
     * Creates the client.
     *
     * @return a client implementing the contract interface - the generated proxy when present, else a
     *     JDK dynamic proxy; never {@code null}
     * @throws dev.vertique.workflow.exception.WorkflowProxyContractException if the contract fails
     *     validation
     */
    C create();
}
