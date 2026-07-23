// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Application-supplied binding types that configure where emitted workflow events are routed.
 *
 * <p>The central type is {@link dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding},
 * which the application provides via Dagger {@code @Provides} to declare the outbox destination
 * for {@code WORKFLOW_EVENT} side-effect intents.
 */
package dev.vertique.workflow.events.binding;
