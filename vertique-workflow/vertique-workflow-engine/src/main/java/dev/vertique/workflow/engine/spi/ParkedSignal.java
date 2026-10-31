// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * A parked {@code SIGNAL} command as the segment that reaches a wait reads it: identity, position and address, without
 * the payload.
 *
 * <p>INTERNAL: framework wiring between the executor and the command repository.
 *
 * @param commandId  the command's row id
 * @param seq        the command's position in its instance's queue
 * @param signalName the signal's name
 * @param forkStepId the fork step a branch-addressed signal targets, or {@code null} for an instance-addressed one
 * @param branchId   the branch a branch-addressed signal targets, or {@code null} for an instance-addressed one
 * @param waitId     the id of the wait entry a signal delivered by key matched, or {@code null} for any other signal
 * @param recordedAt the database time at which the signal was recorded
 */
public record ParkedSignal(
        long commandId,
        long seq,
        String signalName,
        @Nullable String forkStepId,
        @Nullable String branchId,
        @Nullable String waitId,
        Instant recordedAt) {}
