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
 * Verifies {@link TaskFilter}: the {@link TaskFilter#empty()} factory, partial construction via
 * {@code with*} fluent methods, and that fields not set remain null.
 */
class TaskFilterTest {

    // --- Empty filter ---

    @Nested
    @DisplayName("empty() factory")
    class EmptyFilter {

        @Test
        @DisplayName("all fields are null")
        void allFieldsNull() {
            TaskFilter f = TaskFilter.empty();

            assertThat(f.assigneeUser()).isNull();
            assertThat(f.assigneeRole()).isNull();
            assertThat(f.assigneeQueue()).isNull();
            assertThat(f.workflowId()).isNull();
            assertThat(f.status()).isNull();
            assertThat(f.dueBefore()).isNull();
            assertThat(f.subjectType()).isNull();
            assertThat(f.subjectId()).isNull();
        }

        @Test
        @DisplayName("two empty() calls are equal")
        void twoEmptyCallsAreEqual() {
            assertThat(TaskFilter.empty()).isEqualTo(TaskFilter.empty());
        }
    }

    // --- Partial / fluent wither ---

    @Nested
    @DisplayName("fluent with* methods")
    class FluentWithers {

        @Test
        @DisplayName("withAssigneeUser sets only assigneeUser; others remain null")
        void withAssigneeUser() {
            TaskFilter f = TaskFilter.empty().withAssigneeUser("alice");

            assertThat(f.assigneeUser()).isEqualTo("alice");
            assertThat(f.assigneeRole()).isNull();
            assertThat(f.assigneeQueue()).isNull();
            assertThat(f.workflowId()).isNull();
            assertThat(f.status()).isNull();
        }

        @Test
        @DisplayName("withAssigneeRole sets only assigneeRole; others remain null")
        void withAssigneeRole() {
            TaskFilter f = TaskFilter.empty().withAssigneeRole("compliance");

            assertThat(f.assigneeRole()).isEqualTo("compliance");
            assertThat(f.assigneeUser()).isNull();
            assertThat(f.assigneeQueue()).isNull();
        }

        @Test
        @DisplayName("withAssigneeQueue sets only assigneeQueue; others remain null")
        void withAssigneeQueue() {
            TaskFilter f = TaskFilter.empty().withAssigneeQueue("review-q");

            assertThat(f.assigneeQueue()).isEqualTo("review-q");
            assertThat(f.assigneeUser()).isNull();
            assertThat(f.assigneeRole()).isNull();
        }

        @Test
        @DisplayName("withWorkflowId sets only workflowId")
        void withWorkflowId() {
            WorkflowInstanceId wid = new WorkflowInstanceId(UUID.randomUUID());
            TaskFilter f = TaskFilter.empty().withWorkflowId(wid);

            assertThat(f.workflowId()).isEqualTo(wid);
            assertThat(f.assigneeUser()).isNull();
        }

        @Test
        @DisplayName("withStatus sets only status")
        void withStatus() {
            TaskFilter f = TaskFilter.empty().withStatus(TaskStatus.OPEN);

            assertThat(f.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(f.assigneeUser()).isNull();
        }

        @Test
        @DisplayName("withDueBefore sets only dueBefore")
        void withDueBefore() {
            Instant deadline = Instant.parse("2026-06-01T00:00:00Z");
            TaskFilter f = TaskFilter.empty().withDueBefore(deadline);

            assertThat(f.dueBefore()).isEqualTo(deadline);
            assertThat(f.assigneeUser()).isNull();
        }

        @Test
        @DisplayName("withSubjectType sets only subjectType")
        void withSubjectType() {
            TaskFilter f = TaskFilter.empty().withSubjectType("order");

            assertThat(f.subjectType()).isEqualTo("order");
            assertThat(f.subjectId()).isNull();
        }

        @Test
        @DisplayName("withSubjectId sets only subjectId")
        void withSubjectId() {
            TaskFilter f = TaskFilter.empty().withSubjectId("order-42");

            assertThat(f.subjectId()).isEqualTo("order-42");
            assertThat(f.subjectType()).isNull();
        }

        @Test
        @DisplayName("chained withers accumulate without clobbering prior fields")
        void chainedWithers() {
            Instant deadline = Instant.parse("2026-12-31T00:00:00Z");
            TaskFilter f = TaskFilter.empty()
                    .withAssigneeUser("alice")
                    .withStatus(TaskStatus.OPEN)
                    .withDueBefore(deadline)
                    .withSubjectType("order")
                    .withSubjectId("order-99");

            assertThat(f.assigneeUser()).isEqualTo("alice");
            assertThat(f.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(f.dueBefore()).isEqualTo(deadline);
            assertThat(f.subjectType()).isEqualTo("order");
            assertThat(f.subjectId()).isEqualTo("order-99");
            assertThat(f.assigneeRole()).isNull();
            assertThat(f.assigneeQueue()).isNull();
        }

        @Test
        @DisplayName("withAssigneeUser(null) clears the field")
        void withNullClearsField() {
            TaskFilter f = TaskFilter.empty().withAssigneeUser("alice").withAssigneeUser(null);

            assertThat(f.assigneeUser()).isNull();
        }
    }
}
