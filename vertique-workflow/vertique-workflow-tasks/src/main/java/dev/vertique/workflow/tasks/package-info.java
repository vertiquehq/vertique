// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Human task action APIs.
 *
 * <p>Public {@link dev.vertique.workflow.tasks.TaskService} for list/get/complete/reassign
 * with typed-per-decision payload binding, idempotent commands, and {@code WorkflowActor}
 * audit identity. Due-dates reuse cycle-2's {@code WORKFLOW_TIMER} infrastructure via the
 * workflow instance's {@code (wait_type=TASK, wait_aux_id)} slot.
 *
 * <p>Status: Incubating — promoted to Stable at cycle-4 close.
 */
package dev.vertique.workflow.tasks;
