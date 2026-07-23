// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed history-payload records for cycle-3 human-task {@link dev.vertique.workflow.state.WorkflowEntryType}
 * values.
 *
 * <p>Co-located with {@link dev.vertique.workflow.state.WorkflowEntryType} in workflow-core so
 * that the entry type enum and its payload schemas are in the same module. Cycle-2 timer payloads
 * live in {@code vertique-workflow-postgresql} (package-private to the engine); cycle-3 task
 * payloads are public and placed here so that both the engine and the task-service module can
 * reference them without a cross-module dependency.
 */
package dev.vertique.workflow.state.payload;
