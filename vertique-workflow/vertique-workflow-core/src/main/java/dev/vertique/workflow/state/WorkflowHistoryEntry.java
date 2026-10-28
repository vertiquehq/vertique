// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import jakarta.annotation.Nullable;
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
 * <p>The components after {@code recordedAt} are filled only by
 * {@link dev.vertique.workflow.history.WorkflowInstanceHistoryService}. An entry written before the decision log
 * existed reads with {@code entryVersion} 1 and every later component {@code null}. The history that
 * {@link dev.vertique.workflow.ops.WorkflowOperations#query} returns carries the five original components and the
 * stored {@code entryVersion}; every later component is {@code null} there.
 *
 * @param instanceId identifier of the workflow instance this entry belongs to
 * @param sequence monotonically increasing sequence number within the instance; starts at 1
 * @param entryType typed discriminator identifying the kind of event recorded
 * @param payloadJson JSON-encoded event-specific payload
 * @param recordedAt timestamp when this entry was persisted
 * @param entryVersion the stored version of the entry's format: 1 for an entry written before the decision log, 2
 *     after; {@code null} only when an entry is built without it
 * @param actor who the entry is attributed to, {@code null} when it was not attributed
 * @param onBehalfOf the principal a service acted for, or {@code null}
 * @param attributionStatus how the actor was established ({@code VERIFIED}, {@code ABSENT} or {@code DEGRADED}), or
 *     {@code null}
 * @param executorId the node that executed the work, or {@code null}
 * @param commandId the id of the command the entry belongs to, or {@code null}
 * @param stepId the step the entry concerns, or {@code null}
 * @param outcome what the entry decided, or {@code null}
 * @param definitionKey the definition the instance ran under, or {@code null}
 * @param planHash the hash of the plan that was run, or {@code null}
 * @param stateVersionBefore the version of the instance state before the entry, or {@code null}
 * @param stateVersionAfter the version of the instance state after the entry, or {@code null}
 * @param patchJson the JSON array of changes the entry applied to the state ({@code []} for none), or {@code null}
 */
public record WorkflowHistoryEntry(
        WorkflowInstanceId instanceId,
        long sequence,
        WorkflowEntryType entryType,
        String payloadJson,
        Instant recordedAt,
        @Nullable Integer entryVersion,
        @Nullable HistoryActor actor,
        @Nullable String onBehalfOf,
        @Nullable String attributionStatus,
        @Nullable String executorId,
        @Nullable Long commandId,
        @Nullable String stepId,
        @Nullable String outcome,
        @Nullable DefinitionKey definitionKey,
        @Nullable String planHash,
        @Nullable Long stateVersionBefore,
        @Nullable Long stateVersionAfter,
        @Nullable String patchJson) {

    /**
     * Creates an entry with the five original components: entry version 1 and no other later component.
     *
     * @param instanceId identifier of the workflow instance this entry belongs to
     * @param sequence sequence number within the instance
     * @param entryType kind of event recorded
     * @param payloadJson JSON-encoded event-specific payload
     * @param recordedAt timestamp when this entry was persisted
     */
    public WorkflowHistoryEntry(
            WorkflowInstanceId instanceId,
            long sequence,
            WorkflowEntryType entryType,
            String payloadJson,
            Instant recordedAt) {
        this(
                instanceId,
                sequence,
                entryType,
                payloadJson,
                recordedAt,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
