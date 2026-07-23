// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition;
import dev.vertique.workflow.registry.WorkflowContributor;
import jakarta.inject.Singleton;

/**
 * Dagger module that registers the ALL_REQUIRED fan-out order-fulfillment workflow definition.
 *
 * <p>Contributes an {@link OrderFulfillmentFanOutDefinition}-backed {@link WorkflowContributor}
 * into the {@code Set<WorkflowContributor>} multibinding so that the workflow registry picks it
 * up at startup via {@code WorkflowCoreModule.registry(...)}.
 */
@Module
public abstract class OrderFulfillmentFanOutDefinitionModule {

    /**
     * Contributes the fan-out order-fulfillment definition as a {@link WorkflowContributor}.
     *
     * @param definition the singleton fan-out definition
     * @return a contributor that registers the definition into the workflow registry
     */
    @Provides
    @Singleton
    @IntoSet
    static WorkflowContributor orderFulfillmentFanOutContributor(OrderFulfillmentFanOutDefinition definition) {
        return registry -> registry.register(definition);
    }
}
