// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed audit-identity sum type for workflow operations.
 *
 * <p>Every mutating workflow task operation requires a {@link dev.vertique.workflow.actor.WorkflowActor}
 * so that the append-only history can answer "who initiated this change?" The actor is strictly
 * audit metadata — the runtime never gates a call on the actor identity.
 */
package dev.vertique.workflow.actor;
