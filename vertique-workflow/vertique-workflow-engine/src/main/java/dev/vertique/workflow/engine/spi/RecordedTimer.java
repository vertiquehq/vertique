// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * A timer as the command repository recorded it: the timer row and its {@code TIMER} command.
 *
 * <p>INTERNAL: framework wiring between the engine and the command repository.
 *
 * @param timerId   the timer row's id
 * @param commandId the command's row id
 * @param fireAt    the fire time, read from the database clock when the command was recorded
 */
public record RecordedTimer(UUID timerId, long commandId, Instant fireAt) {}
