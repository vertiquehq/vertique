// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.sideeffect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that all expected {@link IntentKind} enum constants are present, including the
 * cycle-4 {@link IntentKind#WORKFLOW_EVENT} value.
 */
class IntentKindTest {

    @Test
    @DisplayName("all 5 expected constants are present including WORKFLOW_EVENT")
    void allConstantsPresent() {
        assertThat(IntentKind.values())
                .extracting(IntentKind::name)
                .containsExactlyInAnyOrder("SERVICE", "WORKFLOW_TIMER", "DELAYED_JOB", "KAFKA", "WORKFLOW_EVENT");
    }

    @Test
    @DisplayName("WORKFLOW_EVENT is present")
    void workflowEventPresent() {
        assertThat(IntentKind.valueOf("WORKFLOW_EVENT")).isEqualTo(IntentKind.WORKFLOW_EVENT);
    }
}
