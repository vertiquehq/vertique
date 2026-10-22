// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.NamedStateReducer;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.callbacks.StateReducerContributor;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import java.util.List;

/**
 * What differs between the nodes of a scenario: the code definitions a node contributes, the type names it lists in
 * addition to the generated catalog, and the named callbacks the YAML documents use.
 */
@Module
final class ScenarioWiring {

    /** The start mapper the tenant YAML document names. */
    static final String PURCHASE_START_MAPPER = "scenario.purchase.fromStart";

    /** The reducer the tenant YAML document names for the finance decision. */
    static final String KEEP_STATE_REDUCER = "scenario.purchase.keepState";

    /** The reducer the tenant YAML document names for the audit signal. */
    static final String APPLY_AUDIT_REDUCER = "scenario.purchase.applyAudit";

    private final List<WorkflowDefinition<?, ?>> definitions;
    private final List<WorkflowTypeCatalogEntry> catalogEntries;

    ScenarioWiring(List<WorkflowDefinition<?, ?>> definitions, List<WorkflowTypeCatalogEntry> catalogEntries) {
        this.definitions = List.copyOf(definitions);
        this.catalogEntries = List.copyOf(catalogEntries);
    }

    @Provides
    @IntoSet
    WorkflowContributor codeDefinitions() {
        return registry -> definitions.forEach(registry::register);
    }

    @Provides
    @IntoSet
    WorkflowTypeCatalog listedTypes() {
        return () -> catalogEntries;
    }

    @Provides
    @IntoSet
    static StartStateMapperContributor purchaseStartMapper() {
        return builder -> builder.register(new NamedStartStateMapper<>(
                PURCHASE_START_MAPPER, StartPurchase.class, PurchaseState.class, PurchaseState::from));
    }

    @Provides
    @IntoSet
    static StateReducerContributor purchaseReducers() {
        return builder -> {
            builder.register(
                    new NamedStateReducer<>(KEEP_STATE_REDUCER, PurchaseState.class, (state, decision) -> state));
            builder.register(new NamedStateReducer<>(
                    APPLY_AUDIT_REDUCER,
                    PurchaseState.class,
                    (state, audit) -> state.withNote("audited by " + ((PoAudited) audit).by())));
        };
    }
}
