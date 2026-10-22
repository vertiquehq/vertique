// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.types.WorkflowType;

/**
 * Payload of the audit signal.
 *
 * @param by who audited
 */
@WorkflowType("po-audited")
public record PoAudited(String by) {}
