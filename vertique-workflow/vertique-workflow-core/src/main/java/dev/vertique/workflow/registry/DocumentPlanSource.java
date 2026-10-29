// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import java.util.Optional;

/**
 * INTERNAL: framework wiring between the registry and the engine's plan cache; not an application extension point.
 *
 * <p>The registry answers the keys of the plans it holds itself and hands every other key to its source. The engine
 * binds its plan cache as the source of the {@link DefaultWorkflowRegistry}. Applications neither implement nor call
 * this interface.
 */
public interface DocumentPlanSource {

    /**
     * Loads or returns the cached plan. Fails as {@link WorkflowRegistry#load(DefinitionKey)} does: with
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionNotFoundException} when no row supplies the plan, with a
     * {@link dev.vertique.workflow.exception.WorkflowDefinitionException} subtype when the plan cannot be built, or with
     * the store failure when the row cannot be read. Only the first two are remembered.
     *
     * @param key the scope, definition id and version
     * @return a future of the plan
     */
    Future<RuntimeWorkflow> load(DefinitionKey key);

    /**
     * Returns the plan if it is resident now. It never loads, builds or does I/O, so it is safe to call from any
     * thread, including an event loop and a transaction.
     *
     * @param key the scope, definition id and version
     * @return the resident plan, or empty
     */
    Optional<RuntimeWorkflow> resident(DefinitionKey key);
}
