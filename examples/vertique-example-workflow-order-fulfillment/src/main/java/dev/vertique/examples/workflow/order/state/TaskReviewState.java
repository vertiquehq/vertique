// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.state;

/**
 * Durable state of the task-review workflow instance.
 *
 * <p>Serialized to/from JSONB by the workflow engine. The single {@code result} field captures the
 * outcome string produced when a decision is submitted — e.g., {@code "approved:needs-fix"} or
 * {@code "rejected:out-of-scope"}.
 *
 * @param result the outcome string; set by the decision applicator and null before a decision is
 *     submitted
 */
public record TaskReviewState(String result) {}
