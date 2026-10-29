// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * Payload of the history entries that record what became of a recorded {@code TIMER} command: received, completed or
 * stale. It names the command, its timer and, for an outcome with a reason, the framework-composed reason; it never
 * carries a payload value or exception text.
 *
 * @param commandId the command's row id
 * @param kind      the command kind
 * @param timerKind the timer kind: {@code FIRE}, {@code TIMEOUT}, {@code DUE} or {@code REMINDER}
 * @param timerId   the timer row the command fires
 * @param reason    the sanitized reason, or {@code null} when the outcome has none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record TimerCommandHistoryPayload(
        long commandId,
        String kind,
        @Nullable String timerKind,
        @Nullable UUID timerId,
        @Nullable String reason) {}
