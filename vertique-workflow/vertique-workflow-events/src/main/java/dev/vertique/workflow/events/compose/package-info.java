// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Startup compose validators for the workflow-events module.
 *
 * <p>The validator in this package runs at Dagger graph construction time to assert that the
 * application's outbox destination handler configuration is valid for delivering
 * {@code WORKFLOW_EVENT} side-effect intents.
 */
package dev.vertique.workflow.events.compose;
