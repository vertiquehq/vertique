// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

/**
 * INTERNAL: whether the application requires identity carriage for the outbox relay and for the workflow, as the
 * executor start needs to know it. A segment's intents and an executor drive bind no identity snapshot, so a required
 * carriage cannot be met on them.
 *
 * @param required         whether identity carriage is {@code REQUIRED} for the outbox relay
 * @param workflowRequired whether identity carriage is {@code REQUIRED} for the workflow
 */
public record OutboxRelayCarriage(boolean required, boolean workflowRequired) {

    /**
     * No carriage is required.
     *
     * @return the value for an application that requires none
     */
    public static OutboxRelayCarriage none() {
        return new OutboxRelayCarriage(false, false);
    }
}
