// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

/**
 * Discriminator for the delivery mechanism of a {@link WorkflowSideEffectIntent}.
 *
 * <p>The {@link WorkflowSideEffectRecorder} SPI uses this enum to identify which recorder
 * implementation owns each intent. Exactly one recorder must be registered per kind; the
 * {@code RecorderRouter} enforces this invariant at construction time.
 *
 * <p>Cycle 1 only handles {@link #SERVICE} intents. {@link #WORKFLOW_TIMER} and
 * {@link #DELAYED_JOB} support is added in cycle 2. {@link #WORKFLOW_EVENT} is added in cycle 4.
 */
public enum IntentKind {
    /**
     * Intent is delivered to a registered service target via the outbox relay.
     * The payload is published to the {@code workflow_outbox} as a {@code SERVICE} destination.
     */
    SERVICE,

    /**
     * Intent schedules a durable workflow timer (cycle 2+). Handled by the timer recorder
     * in {@code vertique-workflow-delayed}.
     */
    WORKFLOW_TIMER,

    /**
     * Intent is delivered as a delayed job (cycle 2+). Not handled by the cycle-1 engine.
     */
    DELAYED_JOB,

    /**
     * Intent is delivered to a Kafka topic (cycle 2+). Not handled by the cycle-1 engine.
     */
    KAFKA,

    /**
     * Intent publishes a durable workflow lifecycle event via the outbox (cycle 4+). Handled by
     * the event recorder in {@code vertique-workflow-events}.
     */
    WORKFLOW_EVENT
}
