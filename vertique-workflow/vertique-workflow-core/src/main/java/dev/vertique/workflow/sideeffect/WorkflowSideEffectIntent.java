// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Describes a durable side-effect that the workflow engine wants to record transactionally.
 *
 * <p>Side-effect intents are created by the engine during {@code driveTransitions} and routed to
 * the appropriate {@link WorkflowSideEffectRecorder} via {@code RecorderRouter}. The recorder
 * writes the intent durably within the caller's transaction; delivery happens after commit (e.g.,
 * via the outbox relay for {@code SERVICE} intents).
 *
 * <p>Dedup is scoped by {@code (kind, scope, key)} so that two unrelated workflow instances can
 * use the same dedup key without collision.
 *
 * @param kind delivery mechanism for this intent; determines which recorder handles it
 * @param targetId service target, job type, or topic identifier depending on {@code kind}
 * @param payload the request/event payload to deliver; type depends on the target
 * @param headers additional routing or metadata headers to attach to the delivery
 * @param correlation back-reference to the workflow instance and sequence that emitted this intent
 */
public record WorkflowSideEffectIntent(
        IntentKind kind, String targetId, Object payload, Map<String, String> headers, Correlation correlation) {

    /**
     * Back-reference identifying the workflow instance, sequence, and definition that emitted this
     * side-effect intent.
     *
     * <p>This information is embedded in the outbox entry's headers so the relay and downstream
     * services can correlate activity back to the originating workflow instance.
     *
     * @param workflowId id of the workflow instance that emitted this intent
     * @param sequence monotonically increasing sequence number within the instance at the point
     *     this intent was recorded
     * @param definitionId id of the workflow definition; included for observability
     * @param stepId id of the {@link dev.vertique.workflow.plan.ServiceDispatchNode} that emitted
     *     this intent; mandatory for all callers (PRD-WF-002 D8a)
     * @param branchTokenId optional branch token id when the dispatch originates from a fan-out
     *     branch; null in single-path workflows
     * @param forkStepId optional fork step id paired with {@code branchTokenId}/{@code branchId}
     * @param branchId optional branch id paired with {@code branchTokenId}/{@code forkStepId}
     */
    public record Correlation(
            WorkflowInstanceId workflowId,
            long sequence,
            String definitionId,
            String stepId,
            @Nullable UUID branchTokenId,
            @Nullable String forkStepId,
            @Nullable String branchId) {

        /**
         * Convenience factory for the single-path (non-branch) case.
         *
         * @param workflowId workflow instance id
         * @param sequence sequence number
         * @param definitionId definition id
         * @param stepId step id (mandatory)
         * @return a correlation with all branch fields null
         */
        public static Correlation singlePath(
                WorkflowInstanceId workflowId, long sequence, String definitionId, String stepId) {
            return new Correlation(workflowId, sequence, definitionId, stepId, null, null, null);
        }
    }
}
