// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Side-effect recorders for the workflow-events module.
 *
 * <p>The {@link dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder} handles
 * {@link dev.vertique.workflow.sideeffect.IntentKind#WORKFLOW_EVENT} intents by writing a
 * transactional outbox entry containing a {@link dev.vertique.workflow.events.WorkflowEventEnvelope}
 * for downstream delivery.
 */
package dev.vertique.workflow.events.recorder;
