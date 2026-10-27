// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

/**
 * Payload of the history entries that record what became of a recorded command that carries nothing to name beyond its
 * kind: rejected or stale. It names the command and, for an outcome with a reason, the framework-composed reason; it
 * never carries a payload value or exception text.
 *
 * @param commandId the command's row id
 * @param kind      the command kind
 * @param reason    the sanitized reason, or {@code null} when the outcome has none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CommandOutcomeHistoryPayload(
        long commandId, String kind, @Nullable String reason) {}
