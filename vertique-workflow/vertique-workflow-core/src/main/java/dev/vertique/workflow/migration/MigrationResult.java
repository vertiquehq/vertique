// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import java.util.Objects;

/**
 * The outcome of a {@link WorkflowMigrationHandler#migrate} call.
 *
 * <p>A sealed interface with exactly two permitted variants:
 * <ul>
 *   <li>{@link AnchorAtInitial} — the instance should be re-pinned to the target version and
 *       restarted from the initial step with the converted state. The engine discards the current
 *       step position and evaluates the target plan's initial step.</li>
 *   <li>{@link ContinueAt} — the instance should be re-pinned to the target version and resume
 *       at a specific step id within the target plan. The step id must exist in the target
 *       plan.</li>
 * </ul>
 *
 * <p>Use the factory methods {@link #anchorAtInitial} and {@link #continueAt} instead of the
 * record constructors directly.
 *
 * @param <T> the target state type produced by the migration handler
 */
public sealed interface MigrationResult<T> permits MigrationResult.AnchorAtInitial, MigrationResult.ContinueAt {

    /**
     * Returns the converted workflow state to persist after migration.
     *
     * @return the new state value; never {@code null}
     */
    T newState();

    /**
     * Outcome variant: re-pin to the target version and restart from the initial step.
     *
     * <p>The engine evaluates the first step of the target plan immediately after migration,
     * exactly as if the instance were newly started.
     *
     * @param <T> the target state type
     * @param newState the converted workflow state; must not be {@code null}
     */
    record AnchorAtInitial<T>(T newState) implements MigrationResult<T> {

        /**
         * Compact constructor — validates that {@code newState} is non-null.
         *
         * @param newState the converted state; must not be {@code null}
         */
        public AnchorAtInitial {
            Objects.requireNonNull(newState, "newState");
        }
    }

    /**
     * Outcome variant: re-pin to the target version and resume at a specific step.
     *
     * <p>The supplied {@code stepId} must exist in the target plan. The engine positions the
     * instance at that step and evaluates it immediately.
     *
     * @param <T> the target state type
     * @param newState the converted workflow state; must not be {@code null}
     * @param stepId the step id within the target plan at which to resume; must not be
     *     {@code null} or blank
     */
    record ContinueAt<T>(T newState, String stepId) implements MigrationResult<T> {

        /**
         * Compact constructor — validates that {@code newState} and {@code stepId} are non-null,
         * and that {@code stepId} is not blank.
         *
         * @param newState the converted state; must not be {@code null}
         * @param stepId the resume step id; must not be {@code null} or blank
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code stepId} is blank
         */
        public ContinueAt {
            Objects.requireNonNull(newState, "newState");
            Objects.requireNonNull(stepId, "stepId");
            if (stepId.isBlank()) {
                throw new IllegalArgumentException("stepId must not be blank");
            }
        }
    }

    // --- Factory methods ---

    /**
     * Creates an {@link AnchorAtInitial} result that restarts the instance from the target plan's
     * initial step.
     *
     * @param <T> the target state type
     * @param newState the converted workflow state; must not be {@code null}
     * @return an {@link AnchorAtInitial} wrapping {@code newState}
     * @throws NullPointerException if {@code newState} is {@code null}
     */
    static <T> MigrationResult<T> anchorAtInitial(T newState) {
        return new AnchorAtInitial<>(newState);
    }

    /**
     * Creates a {@link ContinueAt} result that resumes the instance at the given step id within
     * the target plan.
     *
     * @param <T> the target state type
     * @param newState the converted workflow state; must not be {@code null}
     * @param stepId the step id within the target plan at which to resume; must not be
     *     {@code null} or blank
     * @return a {@link ContinueAt} wrapping {@code newState} and {@code stepId}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code stepId} is blank
     */
    static <T> MigrationResult<T> continueAt(T newState, String stepId) {
        return new ContinueAt<>(newState, stepId);
    }
}
