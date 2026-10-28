// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

/**
 * The payload of a {@code COMMAND_RECEIVED} history entry: the command and the copy of its payload that is logged,
 * with the values a bound history redactor names replaced.
 *
 * @param commandId the command's row id
 * @param kind      the command kind
 * @param payload   the logged copy of the command's payload, or {@code null} for a command without one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CommandReceivedHistoryPayload(
        long commandId, String kind, @Nullable Object payload) {}
