// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.history;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * A recorded command that has not settled: the work an instance still owes, in the state the command log holds it.
 *
 * <p>The record carries no payload; {@code lastError} is a reason code plus a sanitized message.
 *
 * @param instanceId  the instance the command belongs to
 * @param sequence    the command's position in the instance's queue, starting at 1
 * @param kind        the command kind, for example {@code SIGNAL}, {@code COMPLETION} or {@code TIMER}
 * @param state       the command state, for example {@code PENDING}, {@code DEFERRED}, {@code PARKED}
 * @param availableAt the instant from which the command may be claimed
 * @param attempts    the number of attempts made so far
 * @param lastError   the reason code and sanitized message of the last failure, or {@code null} when there is none
 */
public record PendingCommand(
        WorkflowInstanceId instanceId,
        long sequence,
        String kind,
        String state,
        Instant availableAt,
        int attempts,
        @Nullable String lastError) {}
