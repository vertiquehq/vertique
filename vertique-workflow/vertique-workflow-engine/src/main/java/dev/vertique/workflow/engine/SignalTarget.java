// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.SegmentCommand;
import io.vertx.core.json.Json;
import jakarta.annotation.Nullable;

/**
 * The name and address a recorded {@code SIGNAL} command carries as its target: the signal's name and, for a
 * branch-addressed signal, the fork step and the branch.
 *
 * @param name the signal's name
 * @param forkStepId the fork step of a branch-addressed signal, or {@code null}
 * @param branchId the branch of a branch-addressed signal, or {@code null}
 */
record SignalTarget(
        String name, @Nullable String forkStepId, @Nullable String branchId) {

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
}
