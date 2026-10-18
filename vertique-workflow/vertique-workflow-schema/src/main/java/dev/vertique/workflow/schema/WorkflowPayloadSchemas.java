// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import java.util.Optional;

/**
 * Read access to the payload schemas of registered workflow definitions.
 *
 * <p>The schemas are generated and checked when a definition is registered; this interface only reads them. Nothing
 * here loads a class, blocks or touches the database, so every method is safe on the event loop. An unknown
 * definition id, version, step or decision yields an empty result, never an exception.
 *
 * <p>The service reports what a definition declares; it does not decide who may see it. Callers authorize access to
 * the definition before exposing a descriptor.
 */
public interface WorkflowPayloadSchemas {

    /**
     * Describes the start payload and the human task decisions of one pinned definition version.
     *
     * @param definitionId the definition id
     * @param definitionVersion the definition version
     * @return the descriptor, or empty when the definition or the version is not registered
     * @throws IllegalStateException if the definition is registered but the schema module's registration validator did
     *     not run for the registry, so a payload type has no schema
     */
    Optional<WorkflowDefinitionDescriptor> describe(String definitionId, long definitionVersion);

    /**
     * Describes one decision of a human task.
     *
     * @param definitionId the definition id
     * @param definitionVersion the definition version
     * @param stepId the id of the human task step
     * @param decisionName the decision name
     * @return the decision, or empty when the definition, version, step or decision is not declared
     * @throws IllegalStateException if the decision's payload type has no schema because the schema module's
     *     registration validator did not run for the registry
     */
    Optional<DecisionDescriptor> decision(
            String definitionId, long definitionVersion, String stepId, String decisionName);
}
