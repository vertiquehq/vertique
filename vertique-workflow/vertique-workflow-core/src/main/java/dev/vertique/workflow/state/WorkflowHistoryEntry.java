// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;

/**
 * An append-only history record for a workflow instance.
 *
 * <p>History entries form a monotonically increasing, gap-free sequence per instance. Known entry
 * types are enumerated by {@link WorkflowEntryType}:
 * <ul>
 *   <li>{@link WorkflowEntryType#START} — instance was created</li>
 *   <li>{@link WorkflowEntryType#SIGNAL_RECEIVED} — a signal was applied to the instance</li>
 *   <li>{@link WorkflowEntryType#SIDE_EFFECT_RECORDED} — a service-dispatch intent was recorded in
 *       the outbox</li>
 *   <li>{@link WorkflowEntryType#COMPENSATING_START} — compensation flow was initiated</li>
 *   <li>{@link WorkflowEntryType#COMPENSATING_STEP} — a single compensating service intent was
 *       recorded</li>
 *   <li>{@link WorkflowEntryType#COMPENSATED} — all compensation steps have been recorded</li>
 *   <li>{@link WorkflowEntryType#COMPLETED} — workflow reached a {@code CompleteNode}</li>
 *   <li>{@link WorkflowEntryType#FAILED} — workflow reached a {@code FailNode}</li>
 *   <li>{@link WorkflowEntryType#CANCELLED} — workflow was cancelled</li>
 *   <li>{@link WorkflowEntryType#RETRIED} — a failed instance was reset to running</li>
 * </ul>
 *
 * @param instanceId identifier of the workflow instance this entry belongs to
 * @param sequence monotonically increasing sequence number within the instance; starts at 1
 * @param entryType typed discriminator identifying the kind of event recorded
 * @param payloadJson JSON-encoded event-specific payload
 * @param recordedAt timestamp when this entry was persisted
 */
public record WorkflowHistoryEntry(
        WorkflowInstanceId instanceId,
        long sequence,
        WorkflowEntryType entryType,
        String payloadJson,
        Instant recordedAt) {}
