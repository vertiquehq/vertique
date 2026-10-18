// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import jakarta.annotation.Nullable;

/**
 * One decision of a human task.
 *
 * @param name the decision name
 * @param nextStepId the step the workflow advances to after the decision is applied
 * @param payload the schema of the decision payload, or {@code null} when the decision takes no payload
 */
public record DecisionDescriptor(
        String name, String nextStepId, @Nullable PayloadSchema payload) {}
