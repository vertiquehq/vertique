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
 * @param output the schema of the output the definition declares, or {@code null} when it declares none
 */
public record WorkflowDefinitionDescriptor(
        String definitionId,
        long definitionVersion,
        @Nullable PayloadSchema startPayload,
        List<HumanTaskStepDescriptor> humanTasks,
        @Nullable PayloadSchema output) {

    /** Copies the list so that the descriptor is immutable. */
    public WorkflowDefinitionDescriptor {
        humanTasks = List.copyOf(humanTasks);
    }

    /**
     * Creates a descriptor of a definition that declares no output. Retained so existing callers keep compiling.
     *
     * @param definitionId the definition id
     * @param definitionVersion the definition version
     * @param startPayload the schema of the start payload, or {@code null} when the definition starts without a payload
     * @param humanTasks the human task steps in plan order
     */
    public WorkflowDefinitionDescriptor(
            String definitionId,
            long definitionVersion,
            @Nullable PayloadSchema startPayload,
            List<HumanTaskStepDescriptor> humanTasks) {
        this(definitionId, definitionVersion, startPayload, humanTasks, null);
    }
}
