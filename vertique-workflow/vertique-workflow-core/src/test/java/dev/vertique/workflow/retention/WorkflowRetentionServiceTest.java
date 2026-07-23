// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.retention;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the {@link WorkflowRetentionService} interface: presence check and correct field
 * shape of the nested {@link WorkflowRetentionService.RetentionResult} and
 * {@link WorkflowRetentionService.PurgeResult} records.
 */
class WorkflowRetentionServiceTest {

    @Test
    @DisplayName("WorkflowRetentionService is an interface")
    void isAnInterface() {
        assertThat(WorkflowRetentionService.class.isInterface()).isTrue();
    }

    // --- RetentionResult record shape ---

    @Nested
    @DisplayName("RetentionResult record")
    class RetentionResultShape {

        @Test
        @DisplayName("archivedCount and moreRowsRemaining fields are accessible")
        void fieldShape() {
            WorkflowRetentionService.RetentionResult result = new WorkflowRetentionService.RetentionResult(5, true);

            assertThat(result.archivedCount()).isEqualTo(5);
            assertThat(result.moreRowsRemaining()).isTrue();
        }

        @Test
        @DisplayName("zero archivedCount with moreRowsRemaining=false is valid")
        void zeroCountFalseMore() {
            WorkflowRetentionService.RetentionResult result = new WorkflowRetentionService.RetentionResult(0, false);

            assertThat(result.archivedCount()).isZero();
            assertThat(result.moreRowsRemaining()).isFalse();
        }
    }

    // --- PurgeResult record shape ---

    @Nested
    @DisplayName("PurgeResult record")
    class PurgeResultShape {

        @Test
        @DisplayName("purgedCount and moreRowsRemaining fields are accessible")
        void fieldShape() {
            WorkflowRetentionService.PurgeResult result = new WorkflowRetentionService.PurgeResult(10, false);

            assertThat(result.purgedCount()).isEqualTo(10);
            assertThat(result.moreRowsRemaining()).isFalse();
        }

        @Test
        @DisplayName("moreRowsRemaining=true is supported")
        void moreRowsRemainingTrue() {
            WorkflowRetentionService.PurgeResult result = new WorkflowRetentionService.PurgeResult(100, true);

            assertThat(result.moreRowsRemaining()).isTrue();
        }
    }
}
