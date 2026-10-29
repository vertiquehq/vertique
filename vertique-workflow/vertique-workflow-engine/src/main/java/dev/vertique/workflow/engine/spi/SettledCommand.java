// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import jakarta.annotation.Nullable;
import java.util.UUID;

/**
 * A command that a bulk write settled, as the command repository reports it so that the engine can finish the rows
 * that belong to it.
 *
 * <p>INTERNAL: framework wiring between the engine and the command repository.
 *
 * @param commandId the command's row id
 * @param kind      the command kind
 * @param taskId    the task a {@code COMPLETION} reserves, or the task of a due-date or reminder {@code TIMER}; {@code
 *                  null} otherwise
 * @param timerId   the timer row a {@code TIMER} command fires, or {@code null}
 */
public record SettledCommand(
        long commandId,
        String kind,
        @Nullable UUID taskId,
        @Nullable UUID timerId) {}
