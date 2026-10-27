// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a segment that enters a timed wait asks the command repository to record: a timer row and the {@code TIMER}
 * command that fires it.
 *
 * <p>INTERNAL: framework wiring between the engine and the command repository.
 *
 * @param workflowId     the instance whose wait the timer belongs to
 * @param purpose        what the timer is for; it decides the command's timer kind: {@code STANDALONE} is {@code FIRE},
 *                       {@code SIGNAL_TIMEOUT} is {@code TIMEOUT}, {@code TASK_DUE} is {@code DUE} and
 *                       {@code TASK_REMINDER} is {@code REMINDER}
 * @param stepId         the step that enters the wait
 * @param taskId         the task of a due-date or reminder timer; {@code null} for the other purposes
 * @param branchTokenId  the branch token that owns the wait, or {@code null} for an instance wait
 * @param forkStepId     the fork step of a branch wait, or {@code null}
 * @param branchId       the branch of a branch wait, or {@code null}
 * @param fireTime       when the timer fires
 * @param signalNames    the signal names a timeout's wait accepts; empty for the other purposes
 */
public record TimerCommandSpec(
        WorkflowInstanceId workflowId,
        TimerPurpose purpose,
        String stepId,
        @Nullable UUID taskId,
        @Nullable UUID branchTokenId,
        @Nullable String forkStepId,
        @Nullable String branchId,
        FireTime fireTime,
        List<String> signalNames) {

    /**
     * Validates the components and copies the signal names.
     *
     * @throws NullPointerException     if a required component is {@code null}
     * @throws IllegalArgumentException if a task is given to a purpose that has none, or missing for one that needs it
     */
    public TimerCommandSpec {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(fireTime, "fireTime");
        signalNames = List.copyOf(Objects.requireNonNull(signalNames, "signalNames"));
        boolean taskScoped = purpose == TimerPurpose.TASK_DUE || purpose == TimerPurpose.TASK_REMINDER;
        if (taskScoped != (taskId != null)) {
            throw new IllegalArgumentException(
                    taskScoped ? "taskId is required for purpose " + purpose : "taskId must be null for " + purpose);
        }
    }

    /**
     * When a timer fires. The time is fixed once, by the database clock, when the command is recorded.
     */
    public sealed interface FireTime {

        /**
         * A fire time given as an absolute instant. An instant that is already past is taken as the time of recording,
         * so a timer never fires before the segment that enters its wait has recorded it.
         *
         * @param at the instant
         */
        record At(Instant at) implements FireTime {
            /**
             * Creates the fire time.
             *
             * @param at the instant
             */
            public At {
                Objects.requireNonNull(at, "at");
            }
        }

        /**
         * A fire time given as a delay after the recording, measured on the database clock.
         *
         * @param delay the delay; not negative
         */
        record After(Duration delay) implements FireTime {
            /**
             * Creates the fire time.
             *
             * @param delay the delay; a negative delay is taken as zero
             */
            public After {
                Objects.requireNonNull(delay, "delay");
                delay = delay.isNegative() ? Duration.ZERO : delay;
            }
        }
    }
}
