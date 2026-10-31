// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.SegmentCommand;
import io.vertx.core.json.Json;
import jakarta.annotation.Nullable;

/**
 * The name and address a recorded {@code SIGNAL} command carries as its target: the signal's name, for a
 * branch-addressed signal the fork step and the branch, and for a delivery matched to a wait record the id of the
 * wait entry it matched.
 *
 * <p>The record is read strictly: a property it does not declare fails the read, and a target written without a
 * declared property reads it as {@code null}.
 *
 * @param name the signal's name
 * @param forkStepId the fork step of a branch-addressed signal, or {@code null}
 * @param branchId the branch of a branch-addressed signal, or {@code null}
 * @param waitId the id of the wait entry a delivery by key matched, or {@code null} for any other signal
 */
record SignalTarget(
        String name,
        @Nullable String forkStepId,
        @Nullable String branchId,
        @Nullable String waitId) {

    /**
     * Reads the target of a recorded signal command.
     *
     * @param command the command
     * @return the target
     */
    static SignalTarget of(SegmentCommand command) {
        if (command.targetJson() == null) {
            throw new IllegalStateException("signal command " + command.commandId() + " records no target");
        }
        return Json.decodeValue(command.targetJson(), SignalTarget.class);
    }

    /**
     * Whether the signal addresses a branch.
     *
     * @return {@code true} for a branch-addressed signal
     */
    boolean branchAddressed() {
        return branchId != null;
    }

    /**
     * Whether the signal was matched to a wait entry, which it then applies to only while that entry is open.
     *
     * @return {@code true} for a signal that carries the id of a wait entry
     */
    boolean correlated() {
        return waitId != null;
    }
}
