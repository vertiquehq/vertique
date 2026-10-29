// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException;
import dev.vertique.workflow.registry.DocumentPlanSource;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Optional;

/**
 * A plan source that holds a fixed set of built plans, standing in for the plan cache of the engine: a key it holds
 * loads and is resident, any other key is not found.
 */
final class FixedPlanSource implements DocumentPlanSource {

    private final Map<DefinitionKey, RuntimeWorkflow> plans;

    FixedPlanSource(Map<DefinitionKey, RuntimeWorkflow> plans) {
        this.plans = Map.copyOf(plans);
    }

    @Override
    public Future<RuntimeWorkflow> load(DefinitionKey key) {
        RuntimeWorkflow plan = plans.get(key);
        return plan == null
                ? Future.failedFuture(new WorkflowDefinitionNotFoundException(key))
                : Future.succeededFuture(plan);
    }

    @Override
    public Optional<RuntimeWorkflow> resident(DefinitionKey key) {
        return Optional.ofNullable(plans.get(key));
    }
}
