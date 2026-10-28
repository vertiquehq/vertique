// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowInstance;

/**
 * Looks up the plan of a full definition key on this node and refuses work the node has no code for. A key the local
 * registry does not hold is not runnable here, whether or not another node can run it, so every lookup of this
 * class fails with {@link WorkflowVersionNotRunnableException} and never changes the instance it was made for.
 */
final class PinnedPlans {

    private PinnedPlans() {}

    /**
     * Resolves the plan an instance is pinned to.
     *
     * @param registry the local registry
     * @param inst the instance whose definition scope, id and version name the plan
     * @return the plan
     * @throws WorkflowVersionNotRunnableException when the local registry holds no plan for the pin
     */
    static RuntimeWorkflow resolve(WorkflowRegistry registry, WorkflowInstance inst) {
        return resolve(
                registry,
                new DefinitionKey(inst.definitionScopeKey(), inst.definitionId(), inst.definitionVersion()),
                inst);
    }

    /**
     * Resolves the plan of a key, for a lookup made on behalf of no instance, such as a start.
     *
     * @param registry the local registry
     * @param key the full definition key
     * @return the plan
     * @throws WorkflowVersionNotRunnableException when the local registry holds no plan for the key
     */
    static RuntimeWorkflow resolve(WorkflowRegistry registry, DefinitionKey key) {
        return resolve(registry, key, null);
    }

    /**
     * Invariant: a lookup made inside a segment is resident-only. {@code WorkflowRegistry.resolve} never loads a
     * document plan, so it can answer only a plan the registry holds (code) or one the executor warmed and holds for
     * the segment: the pin of the command and, for a migration, its target. Both are made resident before the command
     * is claimed and stay held until its segment ends, whatever the plan cache evicts meanwhile. A lookup of any other
     * document key inside a segment is therefore a fault of the caller, not a cache miss to be loaded.
     */
    private static RuntimeWorkflow resolve(WorkflowRegistry registry, DefinitionKey key, WorkflowInstance inst) {
        try {
            return registry.resolve(key);
        } catch (WorkflowVersionNotRunnableException e) {
            throw e;
        } catch (WorkflowVersionPinUnavailableException e) {
            throw new WorkflowVersionNotRunnableException(key, inst == null ? null : inst.id());
        }
    }
}
