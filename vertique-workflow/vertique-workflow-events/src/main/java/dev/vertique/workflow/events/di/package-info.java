// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Dagger dependency injection module for the workflow-events layer.
 *
 * <p>Include {@link dev.vertique.workflow.events.di.WorkflowEventsModule} in the application's
 * Dagger {@code @Component} to wire the {@code WORKFLOW_EVENT} side-effect recorder into the
 * workflow engine's {@code @WorkflowRecorders} multibinding.
 */
package dev.vertique.workflow.events.di;
