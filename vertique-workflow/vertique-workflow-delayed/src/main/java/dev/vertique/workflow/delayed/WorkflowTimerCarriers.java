// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared construction of the {@code workflow-timer} durable carrier descriptor (P2.S0 commit 4,
 * PRD identity-002 §14.6/A9, F5 row binding).
 *
 * <p>The {@code workflow_timers.metadata} recovery copy is a durable envelope distinct from the
 * underlying delayed job's own {@code job_executions.metadata} row: it is signed for this timer's
 * own carrier — not the delayed-job carrier the job execution row uses — so it can be reproduced
 * and verified purely from the {@code workflow_timers} row's own {@code timer_id} /
 * {@code workflow_id} columns at recovery time, with no dependency on the (possibly-replaced)
 * delayed-job execution id.
 *
 * <p>{@code WorkflowTimerSideEffectRecorder} (package {@code dev.vertique.workflow.delayed.recorder})
 * calls {@link #of(UUID, WorkflowInstanceId)} to sign the recovery copy at timer-create time;
 * {@code WorkflowTimerRecoveryService} (package {@code dev.vertique.workflow.delayed.recovery}) calls
 * it again at orphan-recovery time to reproduce the same carrier and verify {@code row.metadata()}
 * against it before re-enqueueing.
 */
public final class WorkflowTimerCarriers {

    /**
     * Durable-target kind identifying the workflow-timer carrier distinctly from the delayed-job
     * carrier. Both durable envelopes are encoded/decoded under the same
     * {@code DispatchBoundary.DELAYED_JOB} boundary identifier, but sign for different carriers —
     * this kind string is what tells them apart.
     */
    public static final String TARGET_KIND = "workflow-timer";

    private WorkflowTimerCarriers() {}

    /**
     * Builds the workflow-timer carrier descriptor for the given timer/workflow pair.
     *
     * <p>Uses the raw stable {@link WorkflowInstanceId#value()} UUID string — never the record's
     * auto-generated {@code toString()} — as the target address, so the carrier is fully
     * reproducible from the {@code workflow_timers.workflow_id} column alone.
     *
     * @param timerId    the timer's stable UUID; used as the carrier id, reproducible from
     *                   {@code workflow_timers.timer_id}
     * @param workflowId the owning workflow instance id; its raw UUID value is used as the target
     *                   address, reproducible from {@code workflow_timers.workflow_id}
     * @return the carrier descriptor identifying this exact timer row
     */
    public static DurableCarrierDescriptor of(UUID timerId, WorkflowInstanceId workflowId) {
        return new DurableCarrierDescriptor(
                timerId.toString(),
                new DurableTarget(TARGET_KIND, workflowId.value().toString(), Optional.empty()));
    }
}
