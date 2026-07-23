// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * Race-safety classification for a branch in a fan-out/fan-in fork group.
 *
 * <p>Race-safety governs whether a branch may be used inside a {@link FirstSuccessJoinPolicy} or
 * {@link FirstFailureJoinPolicy} race join. The plan validator rejects race joins whose branches
 * declare {@link #NORMAL}; only {@link #CANCEL_SAFE} or {@link #IGNORE_LATE_RESULT_SAFE} branches
 * may participate. This is one half of the race-safety enforcement described in PRD-WF-002 §A.4.6;
 * the other half is service-target metadata validated against {@code RaceSafetyTargetRegistry}.
 */
public enum RaceSafety {

    /**
     * Default; branch is NOT cancel-safe and MUST NOT participate in a race join. Service work in
     * a {@code NORMAL} branch may create external commitments that would require compensation if
     * cancelled — the runtime cannot guarantee that compensation runs after a race winner is
     * elected.
     */
    NORMAL,

    /**
     * Branch work can be cancelled without compensation. Losing branches in a race join can be
     * cancelled mid-flight; their open timers and human tasks are cancelled and any in-flight
     * dispatch results are dropped.
     */
    CANCEL_SAFE,

    /**
     * Branch dispatches are idempotent and safe to ignore late results. Losing branches in a race
     * join continue running but their results are recorded as ignored late losers; the workflow
     * state is not mutated and no compensation is triggered.
     */
    IGNORE_LATE_RESULT_SAFE
}
