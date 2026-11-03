// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

            assertThat(q.statuses()).isEmpty();
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

            assertThat(q.statuses()).containsExactly(WorkflowStatus.RUNNING);
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
            assertThat(q.statuses()).isEmpty();
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
            assertThat(q.statuses()).isEmpty();
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
            assertThat(q.statuses()).isEmpty();
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
            assertThat(updated.statuses()).isEmpty();
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
            assertThat(updated.statuses()).isEmpty();
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

            assertThat(updated.statuses()).containsExactly(WorkflowStatus.WAITING);
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
            assertThat(updated.statuses()).containsExactly(WorkflowStatus.RUNNING);
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
            assertThat(updated.statuses()).containsExactly(WorkflowStatus.COMPLETED);
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

    // --- statuses ---

    @Nested
    @DisplayName("statuses filter")
    class Statuses {

        @Test
        @DisplayName("none, a null set, an empty set and a null single status are all the same query")
        void absentAndEmptyAreEqual() {
            WorkflowInstanceQuery none = WorkflowInstanceQuery.none();

            assertThat(none.withStatuses(null)).isEqualTo(none);
            assertThat(none.withStatuses(List.of())).isEqualTo(none);
            assertThat(none.withStatus(WorkflowStatus.FAILED).withStatus(null)).isEqualTo(none);
            assertThat(WorkflowInstanceQuery.byStatuses(null)).isEqualTo(none);
            assertThat(WorkflowInstanceQuery.byStatuses(Set.of())).isEqualTo(none);
        }

        @Test
        @DisplayName("a query matches the union: byStatuses and withStatuses keep every member once")
        void keepsEveryMemberOnce() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.byStatuses(
                    List.of(WorkflowStatus.FAILED, WorkflowStatus.EXPIRED, WorkflowStatus.FAILED));

            assertThat(q.statuses()).containsExactlyInAnyOrder(WorkflowStatus.FAILED, WorkflowStatus.EXPIRED);
            assertThat(WorkflowInstanceQuery.none().withStatuses(q.statuses())).isEqualTo(q);
        }

        @Test
        @DisplayName("a single status is the one-member set, whichever way it is given")
        void singleStatusIsAOneMemberSet() {
            WorkflowInstanceQuery viaSingle = WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING);

            assertThat(viaSingle).isEqualTo(WorkflowInstanceQuery.byStatuses(Set.of(WorkflowStatus.RUNNING)));
            assertThat(WorkflowInstanceQuery.none().withStatus(WorkflowStatus.RUNNING))
                    .isEqualTo(viaSingle);
            assertThat(viaSingle.statuses()).containsExactly(WorkflowStatus.RUNNING);
        }

        @Test
        @DisplayName("withStatus replaces the whole set rather than adding to it")
        void withStatusReplacesTheSet() {
            WorkflowInstanceQuery q = WorkflowInstanceQuery.byStatuses(
                            Set.of(WorkflowStatus.FAILED, WorkflowStatus.EXPIRED))
                    .withStatus(WorkflowStatus.WAITING);

            assertThat(q.statuses()).containsExactly(WorkflowStatus.WAITING);
        }

        @Test
        @DisplayName("the set is copied: changing the source later or through the accessor changes nothing")
        void setIsCopiedAndUnmodifiable() {
            List<WorkflowStatus> source = new ArrayList<>(List.of(WorkflowStatus.FAILED));
            WorkflowInstanceQuery q = WorkflowInstanceQuery.byStatuses(source);

            source.add(WorkflowStatus.EXPIRED);

            assertThat(q.statuses()).containsExactly(WorkflowStatus.FAILED);
            assertThatThrownBy(() -> q.statuses().add(WorkflowStatus.EXPIRED))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null member is rejected")
        void nullMemberIsRejected() {
            List<WorkflowStatus> withNull = Arrays.asList(WorkflowStatus.FAILED, null);

            assertThatNullPointerException().isThrownBy(() -> WorkflowInstanceQuery.byStatuses(withNull));
            assertThatNullPointerException()
                    .isThrownBy(() -> WorkflowInstanceQuery.none().withStatuses(withNull));
        }

        @Test
        @DisplayName("every other setter keeps the statuses")
        void otherSettersKeepTheStatuses() {
            Set<WorkflowStatus> two = EnumSet.of(WorkflowStatus.FAILED, WorkflowStatus.EXPIRED);
            WorkflowInstanceQuery base = WorkflowInstanceQuery.byStatuses(two);

            assertThat(base.withBusinessKey("bk").statuses()).isEqualTo(two);
            assertThat(base.withSubjectType("t").statuses()).isEqualTo(two);
            assertThat(base.withSubjectId("i").statuses()).isEqualTo(two);
            assertThat(base.withSubjectVersion("v").statuses()).isEqualTo(two);
            assertThat(base.withDefinitionId("d").statuses()).isEqualTo(two);
            assertThat(base.withIncludeArchived(true).statuses()).isEqualTo(two);
            assertThat(base.withScopeKey("s").statuses()).isEqualTo(two);
        }

        @Test
        @DisplayName("the previous seven-argument constructor takes at most one status")
        void previousConstructorStillWorks() {
            WorkflowInstanceQuery running =
                    new WorkflowInstanceQuery(WorkflowStatus.RUNNING, null, null, null, null, null, false);
            WorkflowInstanceQuery any = new WorkflowInstanceQuery(null, null, null, null, null, null, false);

            assertThat(running.statuses()).containsExactly(WorkflowStatus.RUNNING);
            assertThat(any.statuses()).isEmpty();
            assertThat(any).isEqualTo(WorkflowInstanceQuery.none());
        }
    }
}
