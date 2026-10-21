// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import java.util.Objects;

/**
 * Collects the selections of one {@link WorkflowClientBuilder} and hands the finished binding to the
 * factory that creates the client.
 *
 * @param <C> the contract interface the built client implements
 */
final class DefaultWorkflowClientBuilder<C> implements WorkflowClientBuilder<C> {

    private final WorkflowClientFactory factory;
    private final Class<C> contract;
    private final String definitionId;
    private final String contractName;
    private String startScope;
    private String targetScope;
    private Long version;

    DefaultWorkflowClientBuilder(
            WorkflowClientFactory factory, Class<C> contract, String definitionId, String contractName) {
        this.factory = factory;
        this.contract = contract;
        this.definitionId = definitionId;
        this.contractName = contractName;
    }

    @Override
    public WorkflowClientBuilder<C> scope(String startScope) {
        this.startScope = Objects.requireNonNull(startScope, "startScope");
        return this;
    }

    @Override
    public WorkflowClientBuilder<C> version(long version) {
        requirePositive(version);
        this.version = version;
        this.targetScope = null;
        return this;
    }

    @Override
    public WorkflowClientBuilder<C> version(String targetScope, long version) {
        Objects.requireNonNull(targetScope, "targetScope");
        requirePositive(version);
        this.version = version;
        this.targetScope = targetScope;
        return this;
    }

    @Override
    public C create() {
        return factory.createClient(
                contract, new WorkflowClientBinding(definitionId, contractName, startScope, targetScope, version));
    }

    private static void requirePositive(long version) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive, was " + version);
        }
    }
}
