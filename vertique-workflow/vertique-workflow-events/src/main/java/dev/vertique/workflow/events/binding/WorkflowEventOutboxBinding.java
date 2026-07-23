// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.binding;

import dev.vertique.inboxoutbox.DestinationType;
import java.util.Objects;

/**
 * App-supplied binding telling the workflow-events module where to route emitted workflow events.
 *
 * <p>Cycle 4 ships single-binding routing: every {@code WORKFLOW_EVENT} intent goes to the same
 * destination. Per-event-type routing (e.g., {@code TASK_REMINDER} to a notifications topic and
 * lifecycle events to a projector) is an additive future extension that would not require an
 * envelope schema change.
 *
 * <p>Provided by the application via Dagger {@code @Provides}; the recorder reads it once at
 * construction time.
 *
 * @param destinationType the outbox destination type — must have a registered
 *                        {@link dev.vertique.inboxoutbox.OutboxDestinationHandler}
 * @param destination     the destination identifier (Kafka topic, service target id, delayed-job
 *                        handler name, etc.)
 */
public record WorkflowEventOutboxBinding(DestinationType destinationType, String destination) {

    /**
     * Compact constructor that validates required fields.
     *
     * @throws NullPointerException     if {@code destinationType} or {@code destination} is null
     * @throws IllegalArgumentException if {@code destination} is blank
     */
    public WorkflowEventOutboxBinding {
        Objects.requireNonNull(destinationType, "destinationType");
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
    }
}
