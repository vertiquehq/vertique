// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.lifecycle.ComposeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowOutboxComposeValidator} participates in the framework's
 * {@link ComposeValidator} contract, so the {@code VALIDATE}-phase lifecycle step discovers it via
 * the {@code Set<ComposeValidator>} multibinding.
 */
class WorkflowOutboxComposeValidatorMarkerTest {

    @Test
    @DisplayName("WorkflowOutboxComposeValidator is a framework ComposeValidator")
    void isComposeValidator() {
        assertTrue(
                ComposeValidator.class.isAssignableFrom(WorkflowOutboxComposeValidator.class),
                "WorkflowOutboxComposeValidator must implement ComposeValidator so the VALIDATE phase materializes it");
    }
}
