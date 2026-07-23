// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.signal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;

/**
 * Wire format for the workflow signal ingress endpoint.
 *
 * <p>Instances are delivered to {@link WorkflowSignalEndpoint#post(WorkflowSignalRequest)} by the
 * services framework after the signal relay dispatches the outbox entry. The
 * {@link WorkflowSignalContributor} wraps each invocation with {@code InboxService.processOnce} to
 * guarantee exactly-once engine transitions even under at-least-once relay delivery.
 *
 * <p>PRD-WF-002 (D11): the optional {@code forkStepId} / {@code branchId} fields target a
 * specific fork-group branch when both are supplied. Behaviour:
 * <ul>
 *   <li>Both null — instance-level signal (existing semantics).</li>
 *   <li>Both non-null — branch-only signal; the engine looks up the matching branch token by
 *       {@code (workflowId, forkStepId, branchId)} and resumes its branch transition.</li>
 *   <li>Exactly one set — rejected at request validation time.</li>
 * </ul>
 *
 * <p>PRD-WF-007 (Contract Appendix C4): the optional {@code metadata} field carries an explicit
 * durable-context carrier — the raw {@code {"context": {…}}} wire shape produced by
 * {@code DurableMetadata.toCarrier()} — that the caller wants bound as the authoritative base for
 * this signal's drive, overriding the ambient capture. {@code null} preserves today's semantics (no
 * explicit carrier; the engine captures the ambient context as the base). The
 * {@link WorkflowSignalContributor} decodes this field via {@code DurableMetadata.fromCarrier(...)}
 * before calling the metadata-aware {@code TransactionalWorkflowOperations#signal} overload.
 *
 * <p><strong>Security:</strong> {@link WorkflowSignalEndpoint#post(WorkflowSignalRequest)} is an
 * internal-relay-only endpoint (see its class javadoc) — {@code metadata} is bound as the
 * authoritative durable-context base and MUST NOT be populated from untrusted or end-user input.
 * Only the relay that produced the carrier (via {@code DurableMetadata#toCarrier()}) may supply it.
 * Authenticating the provenance of a caller-supplied carrier is tracked as a follow-up (ADR-0147).
 *
 * @param workflowId  the workflow instance to signal
 * @param signalName  the name of the signal to deliver; must match a {@code WaitSignalNode}
 *     signal name in the target workflow definition
 * @param payload     the signal payload; type coercion to the declared {@code WaitSignalNode}
 *     payload class is performed by the engine
 * @param dedupKey    caller-supplied deduplication key; used by the inbox layer
 *     ({@code "workflow-signals"} source) to guard transport-level retries and by the engine
 *     ({@code workflow_dedup} table) to guard per-instance signal uniqueness
 * @param forkStepId  optional fork step id; required when {@code branchId} is set
 * @param branchId    optional branch id; required when {@code forkStepId} is set
 * @param metadata    optional explicit durable-context carrier ({@code {"context": {…}}} wire
 *     shape); {@code null} means no explicit carrier is supplied and the engine falls back to
 *     capturing the ambient context as the bind base. <strong>Security:</strong> bound as the
 *     authoritative durable-context base — MUST NOT be populated from untrusted or end-user input
 *     (see the class-level security note; ADR-0147 tracks provenance authentication as a follow-up)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowSignalRequest(
        WorkflowInstanceId workflowId,
        String signalName,
        Object payload,
        String dedupKey,
        @Nullable String forkStepId,
        @Nullable String branchId,
        @Nullable JsonObject metadata) {

    /**
     * Compact constructor enforcing the both-or-neither rule for branch identity.
     */
    public WorkflowSignalRequest {
        if ((forkStepId == null) != (branchId == null)) {
            throw new IllegalArgumentException("forkStepId and branchId must be supplied together; got forkStepId="
                    + forkStepId + ", branchId=" + branchId);
        }
    }

    /**
     * Convenience factory for an instance-level (non-branch) signal request with no explicit
     * durable-context carrier.
     *
     * @param workflowId workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param dedupKey dedup key
     * @return a request with {@code forkStepId}, {@code branchId}, and {@code metadata} set to null
     */
    public static WorkflowSignalRequest instance(
            WorkflowInstanceId workflowId, String signalName, Object payload, String dedupKey) {
        return new WorkflowSignalRequest(workflowId, signalName, payload, dedupKey, null, null, null);
    }

    /**
     * Convenience factory for an instance-level (non-branch) signal request carrying an explicit
     * durable-context carrier.
     *
     * @param workflowId workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param dedupKey dedup key
     * @param metadata explicit durable-context carrier ({@code {"context": {…}}} wire shape)
     * @return a request with {@code forkStepId} and {@code branchId} set to null and the given
     *     {@code metadata} carrier
     */
    public static WorkflowSignalRequest instance(
            WorkflowInstanceId workflowId,
            String signalName,
            Object payload,
            String dedupKey,
            @Nullable JsonObject metadata) {
        return new WorkflowSignalRequest(workflowId, signalName, payload, dedupKey, null, null, metadata);
    }

    /**
     * Convenience factory for a branch-targeted signal request with no explicit durable-context
     * carrier.
     *
     * @param workflowId workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param dedupKey dedup key
     * @param forkStepId fork step id
     * @param branchId branch id
     * @return a request that targets the specified branch, with {@code metadata} set to null
     */
    public static WorkflowSignalRequest branch(
            WorkflowInstanceId workflowId,
            String signalName,
            Object payload,
            String dedupKey,
            String forkStepId,
            String branchId) {
        return new WorkflowSignalRequest(workflowId, signalName, payload, dedupKey, forkStepId, branchId, null);
    }

    /**
     * Convenience factory for a branch-targeted signal request carrying an explicit durable-context
     * carrier.
     *
     * @param workflowId workflow instance id
     * @param signalName signal name
     * @param payload signal payload
     * @param dedupKey dedup key
     * @param forkStepId fork step id
     * @param branchId branch id
     * @param metadata explicit durable-context carrier ({@code {"context": {…}}} wire shape)
     * @return a request that targets the specified branch with the given {@code metadata} carrier
     */
    public static WorkflowSignalRequest branch(
            WorkflowInstanceId workflowId,
            String signalName,
            Object payload,
            String dedupKey,
            String forkStepId,
            String branchId,
            @Nullable JsonObject metadata) {
        return new WorkflowSignalRequest(workflowId, signalName, payload, dedupKey, forkStepId, branchId, metadata);
    }

    /**
     * Returns true if this request targets a specific fork-group branch.
     *
     * @return true when both {@link #forkStepId()} and {@link #branchId()} are non-null
     */
    @JsonIgnore
    public boolean isBranchSignal() {
        return forkStepId != null && branchId != null;
    }
}
