// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import jakarta.annotation.Nullable;

/**
 * A step that waits for parallel branches to complete and merges their results before advancing.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code id} — unique step identifier within the workflow.
 *   <li>{@code policy} — completion policy: {@code "all-required"} (all branches must succeed),
 *       {@code "first-success"} (first successful branch wins), or {@code "first-failure"}
 *       (first failed branch triggers the join).
 *   <li>{@code reducer} — registered branch result reducer id that combines branch results into
 *       the current workflow state.
 *   <li>{@code next} — id of the step to transition to after the join condition is satisfied.
 *   <li>{@code onFailure} — optional id of the step to transition to if the join fails (e.g.,
 *       when policy is {@code "all-required"} and a branch fails). {@code null} if not configured.
 * </ul>
 */
public record JoinStep(
        String id,
        String policy,
        String reducer,
        String next,
        @Nullable String onFailure) implements StepNode {}
