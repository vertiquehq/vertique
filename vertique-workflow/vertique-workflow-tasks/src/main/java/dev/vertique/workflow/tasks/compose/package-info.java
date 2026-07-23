// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Startup composition validator for the workflow-tasks layer.
 *
 * <p>Contains {@link dev.vertique.workflow.tasks.compose.WorkflowTasksComposeValidator},
 * which asserts at Dagger graph construction time that the required task-store and
 * task-callbacks bindings are present.
 */
package dev.vertique.workflow.tasks.compose;
