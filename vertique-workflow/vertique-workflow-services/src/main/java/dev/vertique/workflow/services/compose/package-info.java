// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/** Application-composition validator: {@code WorkflowOutboxComposeValidator} asserts at startup that a {@code SERVICE} {@code OutboxDestinationHandler} is present in the Dagger graph so workflow service intents are relayed after commit. */
package dev.vertique.workflow.services.compose;
