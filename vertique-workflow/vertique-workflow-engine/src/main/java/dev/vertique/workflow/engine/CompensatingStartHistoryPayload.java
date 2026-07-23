// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code COMPENSATING_START} history entry.
 *
 * <p>Records how many completed compensable forward steps will be compensated in this run.
 * Appended when the engine first transitions an instance from {@code FAILED} to
 * {@code COMPENSATING}.
 *
 * @param stepCount the number of compensable forward steps that will be compensated (always &gt;
 *     0 when this entry exists)
 */
record CompensatingStartHistoryPayload(int stepCount) {}
