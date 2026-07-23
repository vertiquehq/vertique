// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Typed payload for a {@code SIDE_EFFECT_RECORDED} history entry.
 *
 * <p>Records the step id and target id at the point a service-dispatch intent is written to the
 * outbox. This payload is a required input for the compensation-matching algorithm: {@code stepId}
 * is used to look up whether a {@link dev.vertique.workflow.plan.ServiceDispatchNode} has a
 * compensation step registered.
 *
 * <p>PRD-WF-002 (D8): the optional {@code branchTokenId} / {@code forkStepId} / {@code branchId}
 * fields scope the entry to a specific fork-group branch when the dispatch originates from a
 * branch. Single-path workflows leave them null. The compensation orchestrator filters history by
 * these fields so a branch's compensation never crosses into a sibling branch (FR-WF-PAR-064/065).
 *
 * <p>{@link JsonInclude.Include#NON_NULL} keeps the wire format identical to the cycle-3 payload
 * for entries that don't carry branch identity, so existing history rows decode unchanged.
 *
 * @param stepId the id of the {@link dev.vertique.workflow.plan.ServiceDispatchNode} that produced
 *     this side effect
 * @param targetId the service target id (e.g. operation name) that received the intent
 * @param branchTokenId optional branch token id when the dispatch originates from a branch
 * @param forkStepId optional fork step id when the dispatch originates from a branch
 * @param branchId optional branch id when the dispatch originates from a branch
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record SideEffectRecordedHistoryPayload(
        String stepId,
        String targetId,
        @Nullable UUID branchTokenId,
        @Nullable String forkStepId,
        @Nullable String branchId) {

    /**
     * Convenience factory for the single-path (non-branch) case.
     *
     * @param stepId the dispatching step id
     * @param targetId the target id
     * @return a payload with all branch fields null
     */
    static SideEffectRecordedHistoryPayload of(String stepId, String targetId) {
        return new SideEffectRecordedHistoryPayload(stepId, targetId, null, null, null);
    }
}
