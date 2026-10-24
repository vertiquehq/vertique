// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/** Per-plan validator: {@code WorkflowOutboxComposeValidator} refuses a plan that dispatches to a service when no {@code SERVICE} {@code OutboxDestinationHandler} is bound or a service target does not resolve, so workflow service intents are relayed after commit. */
package dev.vertique.workflow.services.compose;
