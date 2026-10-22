// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.types.WorkflowType;

/**
 * Payload of the approval signal.
 *
 * @param by who approved
 */
@WorkflowType("po-approved")
public record PoApproved(String by) {}
