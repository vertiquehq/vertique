// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code includeArchived} field on {@link TaskFilter}: default is {@code false},
 * and the fluent {@link TaskFilter#withIncludeArchived(boolean)} wither returns a new instance
 * with all other fields preserved.
 */
class TaskFilterIncludeArchivedTest {

    // --- Default value ---

    @Nested
    @DisplayName("default includeArchived")
    class DefaultValue {

        @Test
        @DisplayName("empty() factory produces includeArchived=false")
        void emptyFilterHasDefaultFalse() {
            assertThat(TaskFilter.empty().includeArchived()).isFalse();
        }
    }

    // --- Fluent wither ---

    @Nested
    @DisplayName("withIncludeArchived fluent wither")
    class FluentWither {

        @Test
        @DisplayName("withIncludeArchived(true) flips the flag")
        void withTrueFlipsFlag() {
            TaskFilter f = TaskFilter.empty().withIncludeArchived(true);
            assertThat(f.includeArchived()).isTrue();
        }

        @Test
        @DisplayName("withIncludeArchived(false) keeps the flag false")
        void withFalseKeepsFlagFalse() {
            TaskFilter f = TaskFilter.empty().withIncludeArchived(true).withIncludeArchived(false);
            assertThat(f.includeArchived()).isFalse();
        }

        @Test
        @DisplayName("withIncludeArchived(true) preserves all other fields")
        void withTruePreservesOtherFields() {
            WorkflowInstanceId wid = new WorkflowInstanceId(UUID.randomUUID());
            Instant deadline = Instant.parse("2026-12-31T00:00:00Z");

            TaskFilter original = TaskFilter.empty()
                    .withAssigneeUser("alice")
                    .withStatus(TaskStatus.OPEN)
                    .withWorkflowId(wid)
                    .withDueBefore(deadline)
                    .withSubjectType("order")
                    .withSubjectId("order-42");

            TaskFilter withArchived = original.withIncludeArchived(true);

            assertThat(withArchived.includeArchived()).isTrue();
            assertThat(withArchived.assigneeUser()).isEqualTo("alice");
            assertThat(withArchived.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(withArchived.workflowId()).isEqualTo(wid);
            assertThat(withArchived.dueBefore()).isEqualTo(deadline);
            assertThat(withArchived.subjectType()).isEqualTo("order");
            assertThat(withArchived.subjectId()).isEqualTo("order-42");
        }

        @Test
        @DisplayName("withIncludeArchived returns a new instance (immutability)")
        void returnsNewInstance() {
            TaskFilter original = TaskFilter.empty();
            TaskFilter withArchived = original.withIncludeArchived(true);

            assertThat(withArchived).isNotSameAs(original);
            assertThat(original.includeArchived()).isFalse();
        }
    }
}
