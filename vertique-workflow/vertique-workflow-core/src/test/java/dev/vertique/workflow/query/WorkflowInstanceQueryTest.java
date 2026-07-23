// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowInstanceQuery}: factory methods, fluent {@code with*} setters,
 * field isolation (setters do not clobber unrelated fields), and null-safety of
 * {@link WorkflowInstanceQuery#bySubject(WorkflowSubjectRef)}.
 */
class WorkflowInstanceQueryTest {

    // --- none() factory ---

    @Nested
    @DisplayName("none() factory")
    class NoneFactory {

        @Test
        @DisplayName("all nullable fields are null and includeArchived is false")
        void allFieldsNullAndArchivedFalse() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.none();

            assertThat(q.status()).isNull();
            assertThat(q.businessKey()).isNull();
            assertThat(q.subjectType()).isNull();
            assertThat(q.subjectId()).isNull();
            assertThat(q.subjectVersion()).isNull();
            assertThat(q.definitionId()).isNull();
            assertThat(q.includeArchived()).isFalse();
        }

        @Test
        @DisplayName("two none() calls are equal")
        void twoNoneCallsAreEqual() {
            assertThat(WorkflowInstanceQuery.none()).isEqualTo(WorkflowInstanceQuery.none());
        }
    }

    // --- byStatus() factory ---

    @Nested
    @DisplayName("byStatus() factory")
    class ByStatusFactory {

        @Test
        @DisplayName("populates status only; all other fields are null/false")
        void populatesStatusOnly() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING);

            assertThat(q.status()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(q.businessKey()).isNull();
            assertThat(q.subjectType()).isNull();
            assertThat(q.subjectId()).isNull();
            assertThat(q.subjectVersion()).isNull();
            assertThat(q.definitionId()).isNull();
            assertThat(q.includeArchived()).isFalse();
        }
    }

    // --- byDefinitionId() factory ---

    @Nested
    @DisplayName("byDefinitionId() factory")
    class ByDefinitionIdFactory {

        @Test
        @DisplayName("populates definitionId only; all other fields are null/false")
        void populatesDefinitionIdOnly() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.byDefinitionId("article-publish");

            assertThat(q.definitionId()).isEqualTo("article-publish");
            assertThat(q.status()).isNull();
            assertThat(q.businessKey()).isNull();
            assertThat(q.subjectType()).isNull();
            assertThat(q.subjectId()).isNull();
            assertThat(q.subjectVersion()).isNull();
            assertThat(q.includeArchived()).isFalse();
        }
    }

    // --- bySubject() factory ---

    @Nested
    @DisplayName("bySubject() factory")
    class BySubjectFactory {

        @Test
        @DisplayName("populates subjectType, subjectId, and subjectVersion from a versioned ref")
        void versionedRef() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-1", "v3");
            WorkflowInstanceQuery q = WorkflowInstanceQuery.bySubject(ref);

            assertThat(q.subjectType()).isEqualTo("Article");
            assertThat(q.subjectId()).isEqualTo("art-1");
            assertThat(q.subjectVersion()).isEqualTo("v3");
            assertThat(q.status()).isNull();
            assertThat(q.businessKey()).isNull();
            assertThat(q.definitionId()).isNull();
            assertThat(q.includeArchived()).isFalse();
        }

        @Test
        @DisplayName("populates subjectType and subjectId; subjectVersion is null when ref has no version")
        void unversionedRef() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Order", "order-99", null);
            WorkflowInstanceQuery q = WorkflowInstanceQuery.bySubject(ref);

            assertThat(q.subjectType()).isEqualTo("Order");
            assertThat(q.subjectId()).isEqualTo("order-99");
            assertThat(q.subjectVersion()).isNull();
        }

        @Test
        @DisplayName("null ref throws NullPointerException")
        void nullRefThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> WorkflowInstanceQuery.bySubject(null))
                    .withMessage("ref must not be null");
        }
    }

    // --- bySubjectType() factory ---

    @Nested
    @DisplayName("bySubjectType() factory")
    class BySubjectTypeFactory {

        @Test
        @DisplayName("populates subjectType only; subjectId and subjectVersion remain null")
        void populatesSubjectTypeOnly() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.bySubjectType("Product");

            assertThat(q.subjectType()).isEqualTo("Product");
            assertThat(q.subjectId()).isNull();
            assertThat(q.subjectVersion()).isNull();
            assertThat(q.status()).isNull();
            assertThat(q.definitionId()).isNull();
            assertThat(q.includeArchived()).isFalse();
        }
    }

    // --- fluent with* setters ---

    @Nested
    @DisplayName("fluent with* setters")
    class FluentWithers {

        @Test
        @DisplayName("withSubjectVersion replaces the field and preserves all others")
        void withSubjectVersionPreservesOthers() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-1", "v3");
            WorkflowInstanceQuery base = WorkflowInstanceQuery.bySubject(ref);

            WorkflowInstanceQuery updated = base.withSubjectVersion("v4");

            assertThat(updated.subjectVersion()).isEqualTo("v4");
            assertThat(updated.subjectType()).isEqualTo("Article");
            assertThat(updated.subjectId()).isEqualTo("art-1");
            assertThat(updated.status()).isNull();
            assertThat(updated.businessKey()).isNull();
            assertThat(updated.definitionId()).isNull();
            assertThat(updated.includeArchived()).isFalse();
        }

        @Test
        @DisplayName("withSubjectVersion(null) removes the version filter")
        void withSubjectVersionNull() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-1", "v3");
            WorkflowInstanceQuery q = WorkflowInstanceQuery.bySubject(ref).withSubjectVersion(null);

            assertThat(q.subjectVersion()).isNull();
            assertThat(q.subjectType()).isEqualTo("Article");
            assertThat(q.subjectId()).isEqualTo("art-1");
        }

        @Test
        @DisplayName("withIncludeArchived(true) flips the flag and preserves all others")
        void withIncludeArchivedPreservesOthers() {
            WorkflowSubjectRef ref = new WorkflowSubjectRef("Article", "art-1", "v3");
            WorkflowInstanceQuery base = WorkflowInstanceQuery.bySubject(ref);

            WorkflowInstanceQuery updated = base.withIncludeArchived(true);

            assertThat(updated.includeArchived()).isTrue();
            assertThat(updated.subjectType()).isEqualTo("Article");
            assertThat(updated.subjectId()).isEqualTo("art-1");
            assertThat(updated.subjectVersion()).isEqualTo("v3");
            assertThat(updated.status()).isNull();
            assertThat(updated.businessKey()).isNull();
            assertThat(updated.definitionId()).isNull();
        }

        @Test
        @DisplayName("withIncludeArchived flips from default false to true")
        void withIncludeArchivedDefault() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.none().withIncludeArchived(true);
            assertThat(q.includeArchived()).isTrue();
        }

        @Test
        @DisplayName("withStatus replaces status and preserves all others")
        void withStatusPreservesOthers() {
            WorkflowInstanceQuery base = WorkflowInstanceQuery.byDefinitionId("article-publish")
                    .withSubjectType("Article")
                    .withSubjectId("art-1")
                    .withSubjectVersion("v2");

            WorkflowInstanceQuery updated = base.withStatus(WorkflowStatus.WAITING);

            assertThat(updated.status()).isEqualTo(WorkflowStatus.WAITING);
            assertThat(updated.definitionId()).isEqualTo("article-publish");
            assertThat(updated.subjectType()).isEqualTo("Article");
            assertThat(updated.subjectId()).isEqualTo("art-1");
            assertThat(updated.subjectVersion()).isEqualTo("v2");
            assertThat(updated.businessKey()).isNull();
            assertThat(updated.includeArchived()).isFalse();
        }

        @Test
        @DisplayName("withBusinessKey replaces businessKey and preserves all others")
        void withBusinessKeyPreservesOthers() {
            WorkflowInstanceQuery base = WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING);
            WorkflowInstanceQuery updated = base.withBusinessKey("bk-001");

            assertThat(updated.businessKey()).isEqualTo("bk-001");
            assertThat(updated.status()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(updated.subjectType()).isNull();
            assertThat(updated.subjectId()).isNull();
            assertThat(updated.subjectVersion()).isNull();
            assertThat(updated.definitionId()).isNull();
        }

        @Test
        @DisplayName("withDefinitionId replaces definitionId and preserves all others")
        void withDefinitionIdPreservesOthers() {
            WorkflowInstanceQuery base =
                    WorkflowInstanceQuery.bySubjectType("Order").withStatus(WorkflowStatus.COMPLETED);
            WorkflowInstanceQuery updated = base.withDefinitionId("order-flow");

            assertThat(updated.definitionId()).isEqualTo("order-flow");
            assertThat(updated.subjectType()).isEqualTo("Order");
            assertThat(updated.status()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        @Test
        @DisplayName("withIncludeArchived returns a new instance (immutability)")
        void withIncludeArchivedReturnsNewInstance() {
            WorkflowInstanceQuery original = WorkflowInstanceQuery.none();
            WorkflowInstanceQuery updated = original.withIncludeArchived(true);

            assertThat(updated).isNotSameAs(original);
            assertThat(original.includeArchived()).isFalse();
        }
    }
}
