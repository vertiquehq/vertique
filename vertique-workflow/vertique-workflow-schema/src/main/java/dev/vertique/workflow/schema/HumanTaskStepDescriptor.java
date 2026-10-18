// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import java.util.List;

/**
 * One human task step and the decisions it offers.
 *
 * @param stepId the step id
 * @param decisions the decisions in declaration order; an unmodifiable copy
 */
public record HumanTaskStepDescriptor(String stepId, List<DecisionDescriptor> decisions) {

    /** Copies the list so that the descriptor is immutable. */
    public HumanTaskStepDescriptor {
        decisions = List.copyOf(decisions);
    }
}
