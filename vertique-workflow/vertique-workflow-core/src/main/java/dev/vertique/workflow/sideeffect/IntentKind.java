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
 * <p>The workflow modules ship a recorder for {@link #SERVICE} intents and one for
 * {@link #WORKFLOW_EVENT} intents. {@link #DELAYED_JOB} and {@link #KAFKA} have no shipped recorder:
 * an intent of either kind fails routing, and the transition rolls back, unless the application
 * registers a recorder for that kind or declares the kind optional. Workflow timers are not
 * side-effect intents; the engine records them as timer commands.
 */
public enum IntentKind {
    /**
     * Intent is delivered to a registered service target via the outbox relay.
     * The payload is published to the {@code workflow_outbox} as a {@code SERVICE} destination.
     */
    SERVICE,

    /**
     * Intent is delivered as a delayed job. No recorder for this kind ships with the workflow modules.
     */
    DELAYED_JOB,

    /**
     * Intent is delivered to a Kafka topic. No recorder for this kind ships with the workflow modules.
     */
    KAFKA,

    /**
     * Intent publishes a durable workflow lifecycle event via the outbox. Handled by
     * the event recorder in {@code vertique-workflow-events}.
     */
    WORKFLOW_EVENT
}
