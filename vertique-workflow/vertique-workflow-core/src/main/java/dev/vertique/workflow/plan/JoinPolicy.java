// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * Sealed marker for the join semantics applied by a {@link JoinNode}.
 *
 * <p>V1 supports three policies:
 * <ul>
 *   <li>{@link AllRequiredJoinPolicy} — advance only after every branch in the fork group reaches
 *       {@code COMPLETED}; take the failure route if any required branch fails.</li>
 *   <li>{@link FirstSuccessJoinPolicy} — advance on the first branch reaching {@code COMPLETED};
 *       sibling branches are marked {@code SUPERSEDED}.</li>
 *   <li>{@link FirstFailureJoinPolicy} — take the failure route on the first branch reaching
 *       {@code FAILED}/{@code CANCELLED}/{@code EXPIRED}; sibling branches are marked
 *       {@code SUPERSEDED}.</li>
 * </ul>
 *
 * <p>Race policies ({@code FirstSuccessJoinPolicy}, {@code FirstFailureJoinPolicy}) are valid only
 * for race-safe branch groups; the plan validator rejects compensable steps and {@link
 * RaceSafety#NORMAL} branches under those policies (PRD-WF-002 FR-WF-PAR-011/054). Future quorum
 * and partial-success policies will be additive permits with their own state.
 */
public sealed interface JoinPolicy permits AllRequiredJoinPolicy, FirstSuccessJoinPolicy, FirstFailureJoinPolicy {}
