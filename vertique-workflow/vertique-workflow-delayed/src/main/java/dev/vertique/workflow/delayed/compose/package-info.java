// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Startup composition validator for the workflow-delayed layer.
 *
 * <p>Contains {@link dev.vertique.workflow.delayed.compose.WorkflowDelayedComposeValidator},
 * which asserts at Dagger graph construction time that the required delayed-job and job-repository
 * bindings are present.
 */
package dev.vertique.workflow.delayed.compose;
