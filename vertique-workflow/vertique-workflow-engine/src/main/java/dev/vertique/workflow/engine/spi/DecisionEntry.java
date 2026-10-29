// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowEntryType;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * A history entry of the decision log: one engine decision ({@link WorkflowEntryType#DECISION}) or the full state of
 * an instance ({@link WorkflowEntryType#STATE_BASELINE}), with the columns that identify the decision.
 *
 * <p>INTERNAL: framework wiring between the engine's decision log and the history repository. The repository assigns
 * the entry's sequence, in the transaction that holds the instance's execution lock.
 *
 * @param instanceId         the instance
 * @param entryType          {@link WorkflowEntryType#DECISION} or {@link WorkflowEntryType#STATE_BASELINE}
 * @param commandId          the command the entry belongs to, or {@code null} when it belongs to none
 * @param stepId             the step the decision was taken at, or {@code null}
 * @param outcome            the outcome of the decision, at most 40 characters, or {@code null}
 * @param scopeKey           the scope of the definition version the instance is pinned to
 * @param definitionId       the id of the definition the instance is pinned to
 * @param definitionVersion  the version of the definition the instance is pinned to
 * @param planHash           the plan hash of the instance
 * @param stateVersionBefore the state version the decision started from
 * @param stateVersionAfter  the state version the decision led to; equal to {@code stateVersionBefore} when the state did
 *                           not change
 * @param executorId         the node id of the executor, or {@code null} to take it from the command claim
 * @param patchJson          the RFC 6902 patch of the state change as a JSON array, an empty array for no change, or
 *                           {@code null} for none (a baseline, or a change too large to log as a patch)
 * @param stateJson          the full state of a baseline entry, or {@code null}
 * @param recordedAt         when the entry was recorded
 */
public record DecisionEntry(
        WorkflowInstanceId instanceId,
        WorkflowEntryType entryType,
        @Nullable Long commandId,
        @Nullable String stepId,
        @Nullable String outcome,
        String scopeKey,
        String definitionId,
        long definitionVersion,
        String planHash,
        long stateVersionBefore,
        long stateVersionAfter,
        @Nullable String executorId,
        @Nullable String patchJson,
        @Nullable String stateJson,
        Instant recordedAt) {}
