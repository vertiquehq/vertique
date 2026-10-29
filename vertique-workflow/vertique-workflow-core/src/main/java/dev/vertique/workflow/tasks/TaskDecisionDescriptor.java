// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * UI-friendly snapshot of a single decision option for a human task.
 *
 * <p>Stored as part of the {@code workflow_tasks.decisions_snapshot_json} column so that
 * task-query APIs can expose decision metadata to callers without requiring a lookup against the
 * workflow plan. Carries no internal callback ids — those are engine-private.
 *
 * @param name the decision name as declared in the plan (e.g., {@code "approve"}, {@code "reject"})
 * @param payloadTypeName the logical type name of the expected decision payload (its binary name when
 *     the class has no {@code @WorkflowType} name); callers
 *     use this to coerce the raw payload before submitting a
 *     {@link dev.vertique.workflow.ops.TaskCompletionCommand}
 * @param nextStepId the plan step id the workflow will advance to after this decision is applied
 * @param payloadSchema JSON schema of the decision payload, or null when none was captured
 */
public record TaskDecisionDescriptor(
        String name,
        String payloadTypeName,
        String nextStepId,
        @Nullable JsonObject payloadSchema) {

    /**
     * Creates a descriptor without a payload schema.
     *
     * @param name the decision name
     * @param payloadTypeName the logical payload type name
     * @param nextStepId the step the workflow advances to
     */
    public TaskDecisionDescriptor(String name, String payloadTypeName, String nextStepId) {
        this(name, payloadTypeName, nextStepId, null);
    }

    /**
     * Validates that all required fields are non-null and non-blank.
     *
     * @throws NullPointerException if any field is null
     * @throws IllegalArgumentException if any field is blank
     */
    public TaskDecisionDescriptor {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(payloadTypeName, "payloadTypeName");
        if (payloadTypeName.isBlank()) {
            throw new IllegalArgumentException("payloadTypeName must not be blank");
        }
        Objects.requireNonNull(nextStepId, "nextStepId");
        if (nextStepId.isBlank()) {
            throw new IllegalArgumentException("nextStepId must not be blank");
        }
    }
}
