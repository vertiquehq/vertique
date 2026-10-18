// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * The payload contract of one pinned workflow definition version.
 *
 * @param definitionId the definition id
 * @param definitionVersion the definition version
 * @param startPayload the schema of the start payload, or {@code null} when the definition starts without a payload
 * @param humanTasks the human task steps in plan order; an unmodifiable copy
 */
public record WorkflowDefinitionDescriptor(
        String definitionId,
        long definitionVersion,
        @Nullable PayloadSchema startPayload,
        List<HumanTaskStepDescriptor> humanTasks) {

    /** Copies the list so that the descriptor is immutable. */
    public WorkflowDefinitionDescriptor {
        humanTasks = List.copyOf(humanTasks);
    }
}
