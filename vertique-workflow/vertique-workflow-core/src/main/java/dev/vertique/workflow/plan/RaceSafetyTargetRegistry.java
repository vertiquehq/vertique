// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * Registry of declared race-safety classifications for service-dispatch targets.
 *
 * <p>Workflow-plan validation consults this registry when a {@link FirstSuccessJoinPolicy} or
 * {@link FirstFailureJoinPolicy} race join is encountered: every {@link ServiceDispatchNode}
 * reachable in a race branch must resolve to a registered target whose safety is
 * {@link RaceSafety#CANCEL_SAFE} or {@link RaceSafety#IGNORE_LATE_RESULT_SAFE}. Targets without a
 * registered classification default to {@link RaceSafety#NORMAL} — i.e., they are NOT allowed
 * inside race branches (PRD-WF-002 FR-WF-PAR-012).
 *
 * <p>Population: applications declare safe targets at startup by contributing one or more
 * {@link RaceSafetyTargetContributor}s into the {@code Set<RaceSafetyTargetContributor>} Dagger
 * multibinding. The {@link DefaultRaceSafetyTargetRegistry} {@code @Singleton} aggregates them
 * once at construction time. The registry is read-only after construction.
 */
public interface RaceSafetyTargetRegistry {

    /**
     * Looks up the declared race-safety for the given service-dispatch target id.
     *
     * @param targetId the {@link ServiceDispatchNode#targetId()} to look up; non-null
     * @return the declared {@link RaceSafety}, or {@link RaceSafety#NORMAL} if no registration
     *     exists for {@code targetId}
     */
    RaceSafety lookup(String targetId);

    /**
     * Mutable builder for accumulating race-safety registrations.
     *
     * <p>Passed to each {@link RaceSafetyTargetContributor} during registry construction. After
     * the contributor pass completes the builder is sealed — the resulting registry is read-only.
     */
    interface Builder {

        /**
         * Registers {@code safety} for {@code targetId}.
         *
         * <p>If {@code targetId} is already registered with the same safety, the call is a no-op.
         * If it is already registered with a <em>different</em> safety, the builder throws
         * {@link IllegalStateException} — surface the conflict instead of silently downgrading or
         * upgrading the safety guarantee.
         *
         * @param targetId service-dispatch target id; non-null, non-empty
         * @param safety declared race-safety for the target; non-null
         * @return this builder
         * @throws IllegalStateException if {@code targetId} is already registered with a
         *     different {@link RaceSafety}
         */
        Builder register(String targetId, RaceSafety safety);
    }
}
