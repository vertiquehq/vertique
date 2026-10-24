// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.compose;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowOutboxComposeValidator} runs as a per-plan {@link WorkflowDefinitionValidator} and not
 * as a startup {@link ComposeValidator}, so the registry runs it on every plan it builds.
 */
class WorkflowOutboxComposeValidatorMarkerTest {

    @Test
    @DisplayName("WorkflowOutboxComposeValidator is a definition validator and no longer a ComposeValidator")
    void isDefinitionValidatorAndNotComposeValidator() {
        assertTrue(
                WorkflowDefinitionValidator.class.isAssignableFrom(WorkflowOutboxComposeValidator.class),
                "WorkflowOutboxComposeValidator must implement WorkflowDefinitionValidator so the registry runs it per plan");
        assertFalse(
                ComposeValidator.class.isAssignableFrom(WorkflowOutboxComposeValidator.class),
                "WorkflowOutboxComposeValidator must not implement ComposeValidator");
    }
}
