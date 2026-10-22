// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

/**
 * Payload of the review task's approve decision. It carries no logical name, so every stored decision names it by its
 * binary name.
 *
 * @param by who approved
 */
public record OrderApproval(String by) {}
