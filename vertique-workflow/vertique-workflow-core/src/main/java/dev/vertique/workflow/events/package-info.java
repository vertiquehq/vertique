// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Workflow event types for the durable event publishing workstream (cycle 4+).
 *
 * <h2>Type roles</h2>
 * <ul>
 *   <li>{@link dev.vertique.workflow.events.WorkflowEventType} — producer enum used internally by
 *       the engine to identify the event. Never serialized into the wire format; the name string
 *       is extracted via {@link java.lang.Enum#name()} and placed in the envelope.</li>
 *   <li>{@link dev.vertique.workflow.events.WorkflowEventIntent} — internal record that carries
 *       the event payload from the engine to the side-effect recorder. Not exposed outside
 *       {@code workflow-core} and {@code workflow-events}.</li>
 *   <li>{@link dev.vertique.workflow.events.WorkflowEventEnvelope} — stable JSON wire contract
 *       published via the outbox. The {@code eventType} field is a plain {@link java.lang.String}
 *       so that new event types can be added without breaking existing consumers that tolerate
 *       unknown string values.</li>
 * </ul>
 *
 * <h2>Consumer contract</h2>
 * Consumers MUST treat {@code eventType} as a string-based discriminator. Pattern-match
 * defensively: skip or log unknown values rather than throwing. Adding new values to
 * {@link dev.vertique.workflow.events.WorkflowEventType} is non-breaking within
 * {@code schemaVersion=1}.
 */
package dev.vertique.workflow.events;
