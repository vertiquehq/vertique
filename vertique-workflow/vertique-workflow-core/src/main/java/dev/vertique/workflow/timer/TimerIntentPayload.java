// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.timer;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed payload for a {@code WORKFLOW_TIMER} side-effect intent.
 *
 * <p>Lives in {@code workflow-core} (not {@code workflow-postgresql} or {@code workflow-delayed})
 * so that the producer (engine) and consumer (timer recorder) share the type without a
 * sibling-module dependency.
 *
 * <p>The compact constructor enforces the pairing rules between {@link #purpose()} and
 * {@link #taskId()}: task-scoped purposes ({@link TimerPurpose#TASK_DUE} and
 * {@link TimerPurpose#TASK_REMINDER}) require a non-null task id; workflow-scoped purposes
 * ({@link TimerPurpose#STANDALONE} and {@link TimerPurpose#SIGNAL_TIMEOUT}) require a null
 * task id. Violations throw {@link IllegalArgumentException} at construction time.
 *
 * @param fireAt  when the timer should fire (engine-resolved instant); never null
 * @param purpose the timer's role — drives executor dispatch and cancellation routing; never null
 * @param taskId  task identity for {@link TimerPurpose#TASK_DUE} and
 *                {@link TimerPurpose#TASK_REMINDER}; must be non-null for those purposes and
 *                null otherwise (enforced at construction time)
 */
public record TimerIntentPayload(
        Instant fireAt, TimerPurpose purpose, @Nullable UUID taskId) {

    /**
     * Validates field constraints and the taskId-purpose pairing rules.
     *
     * @throws NullPointerException     if {@code fireAt} or {@code purpose} is null
     * @throws IllegalArgumentException if the taskId-purpose pairing is invalid
     */
    public TimerIntentPayload {
        Objects.requireNonNull(fireAt, "fireAt");
        Objects.requireNonNull(purpose, "purpose");
        TimerPurpose.validateTaskIdPairing(purpose, taskId);
    }
}
