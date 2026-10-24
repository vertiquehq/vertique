// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code COMMAND_*} history entry.
 *
 * @param commandId the command's row id
 * @param kind the command kind, for example {@code START}
 */
record CommandHistoryPayload(long commandId, String kind) {}
