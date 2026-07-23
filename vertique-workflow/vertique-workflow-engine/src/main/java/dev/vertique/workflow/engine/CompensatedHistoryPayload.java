// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

/**
 * Typed payload for a {@code COMPENSATED} history entry.
 *
 * <p>Records how many compensation steps were executed in total before the engine transitioned the
 * instance to {@code COMPENSATED}.
 *
 * @param stepCount the number of compensation steps that were recorded (matches the
 *     {@link CompensatingStartHistoryPayload#stepCount()} from the same compensation run)
 */
record CompensatedHistoryPayload(int stepCount) {}
