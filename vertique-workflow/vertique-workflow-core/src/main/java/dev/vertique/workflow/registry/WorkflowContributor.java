// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

/**
 * Functional SPI for contributing workflow definitions to the {@link WorkflowRegistry}.
 *
 * <p>Implementations register one or more workflow definitions by calling
 * {@link WorkflowRegistry#register(dev.vertique.workflow.dsl.WorkflowDefinition)} on the provided
 * registry. Contributors are contributed to the Dagger graph as a {@code Set<WorkflowContributor>}
 * multibinding and are invoked once during application startup by
 * {@code WorkflowCoreModule.registry(...)}.
 *
 * <pre>{@code
 * @Provides @IntoSet
 * static WorkflowContributor orderFulfillmentContributor(OrderFulfillmentDefinition def) {
 *     return registry -> registry.register(def);
 * }
 * }</pre>
 */
@FunctionalInterface
public interface WorkflowContributor {

    /**
     * Contributes one or more workflow definitions to the given registry.
     *
     * @param registry the registry to register definitions into
     */
    void contribute(WorkflowRegistry registry);
}
