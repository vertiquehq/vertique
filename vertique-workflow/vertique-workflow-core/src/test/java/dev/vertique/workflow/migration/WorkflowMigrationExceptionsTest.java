// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.migration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.exception.WorkflowException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Constructor and accessor sanity checks for {@link WorkflowMigrationHandlerMissingException}
 * and {@link WorkflowMigrationIllegalStateException}. Also verifies both are subtypes of
 * {@link WorkflowException}.
 */
class WorkflowMigrationExceptionsTest {

    private static final WorkflowInstanceId INSTANCE_ID =
            new WorkflowInstanceId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    // --- WorkflowMigrationHandlerMissingException ---

    @Nested
    @DisplayName("WorkflowMigrationHandlerMissingException")
    class HandlerMissingExceptionTests {

        private static final String DEF_ID = "order-saga";
        private static final long FROM_VERSION = 1L;
        private static final long TARGET_VERSION = 3L;

        @Test
        @DisplayName("workflowId() returns the id supplied to the constructor")
        void workflowIdAccessor() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            assertThat(ex.workflowId()).isEqualTo(INSTANCE_ID);
        }

        @Test
        @DisplayName("definitionId() returns the definition id supplied to the constructor")
        void definitionIdAccessor() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            assertThat(ex.definitionId()).isEqualTo(DEF_ID);
        }

        @Test
        @DisplayName("fromVersion() returns the source version")
        void fromVersionAccessor() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            assertThat(ex.fromVersion()).isEqualTo(FROM_VERSION);
        }

        @Test
        @DisplayName("targetVersion() returns the target version")
        void targetVersionAccessor() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            assertThat(ex.targetVersion()).isEqualTo(TARGET_VERSION);
        }

        @Test
        @DisplayName("getMessage() includes all identifying fields")
        void messageContainsAllFields() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            String msg = ex.getMessage();
            assertThat(msg)
                    .contains(DEF_ID)
                    .contains(String.valueOf(FROM_VERSION))
                    .contains(String.valueOf(TARGET_VERSION));
        }

        @Test
        @DisplayName("is a subtype of WorkflowException")
        void isSubtypeOfWorkflowException() {
            WorkflowMigrationHandlerMissingException ex =
                    new WorkflowMigrationHandlerMissingException(INSTANCE_ID, DEF_ID, FROM_VERSION, TARGET_VERSION);
            assertThat(ex).isInstanceOf(WorkflowException.class);
        }
    }

    // --- WorkflowMigrationIllegalStateException ---

    @Nested
    @DisplayName("WorkflowMigrationIllegalStateException")
    class IllegalStateExceptionTests {

        private static final String REASON = "instance is in terminal status COMPLETED";

        @Test
        @DisplayName("workflowId() returns the id supplied to the constructor")
        void workflowIdAccessor() {
            WorkflowMigrationIllegalStateException ex = new WorkflowMigrationIllegalStateException(INSTANCE_ID, REASON);
            assertThat(ex.workflowId()).isEqualTo(INSTANCE_ID);
        }

        @Test
        @DisplayName("reason() returns the reason supplied to the constructor")
        void reasonAccessor() {
            WorkflowMigrationIllegalStateException ex = new WorkflowMigrationIllegalStateException(INSTANCE_ID, REASON);
            assertThat(ex.reason()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("getMessage() includes the reason")
        void messageContainsReason() {
            WorkflowMigrationIllegalStateException ex = new WorkflowMigrationIllegalStateException(INSTANCE_ID, REASON);
            assertThat(ex.getMessage()).contains(REASON);
        }

        @Test
        @DisplayName("is a subtype of WorkflowException")
        void isSubtypeOfWorkflowException() {
            WorkflowMigrationIllegalStateException ex = new WorkflowMigrationIllegalStateException(INSTANCE_ID, REASON);
            assertThat(ex).isInstanceOf(WorkflowException.class);
        }
    }
}
