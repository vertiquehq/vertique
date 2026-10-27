// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code workflow-timer} durable carrier descriptor: the carrier the durable context stored in a
 * {@code workflow_timers} row's {@code metadata} is signed for.
 *
 * <p>The descriptor is reproducible from the row's own {@code timer_id} and {@code workflow_id} columns, so metadata
 * written for a timer can be verified against the timer row alone. Its kind and address are the ones timers have always
 * used, so metadata written by earlier releases still verifies.
 */
public final class WorkflowTimerCarriers {

    /** The durable-target kind that tells a timer row's carrier from every other row's. */
    public static final String TARGET_KIND = "workflow-timer";

    private WorkflowTimerCarriers() {
        // static helpers only
    }

    /**
     * Builds the carrier descriptor of a timer row.
     *
     * <p>The address is the raw {@link WorkflowInstanceId#value()} UUID text, never the record's generated
     * {@code toString()}, so the descriptor is reproducible from the {@code workflow_timers.workflow_id} column alone.
     *
     * @param timerId    the timer's id, the carrier id
     * @param workflowId the owning instance; its raw UUID is the target address
     * @return the descriptor identifying that timer row
     */
    public static DurableCarrierDescriptor of(UUID timerId, WorkflowInstanceId workflowId) {
        return new DurableCarrierDescriptor(
                timerId.toString(),
                new DurableTarget(TARGET_KIND, workflowId.value().toString(), Optional.empty()));
    }
}
