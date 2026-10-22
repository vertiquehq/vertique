// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.types.WorkflowType;

/**
 * Payload of the finance task's approve decision.
 *
 * @param by who decided
 */
@WorkflowType("finance-decision")
public record FinanceDecision(String by) {}
