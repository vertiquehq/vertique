// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.lifecycle.ComposeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowReminderComposeValidator} participates in the framework's
 * {@link ComposeValidator} contract, so the {@code VALIDATE}-phase lifecycle step discovers it via
 * the {@code Set<ComposeValidator>} multibinding.
 */
class WorkflowReminderComposeValidatorMarkerTest {

    @Test
    @DisplayName("WorkflowReminderComposeValidator is a framework ComposeValidator")
    void isComposeValidator() {
        assertTrue(
                ComposeValidator.class.isAssignableFrom(WorkflowReminderComposeValidator.class),
                "WorkflowReminderComposeValidator must implement ComposeValidator so the VALIDATE phase materializes it");
    }
}
