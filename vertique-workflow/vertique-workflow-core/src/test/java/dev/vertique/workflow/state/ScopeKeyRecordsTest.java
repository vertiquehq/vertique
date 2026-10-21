// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for the scope keys carried by definition keys, instances, tasks, queries and filters: the default scope
 * is a reserved value that replaces null, a definition key requires a bounded scope, and every copy keeps its scopes.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ScopeKeyRecordsTest {

    private static final WorkflowInstanceId ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-10T01:00:00Z");
    private static final String PLAN_HASH = "a".repeat(64);

    @Test
    void scopeKeysAreRequiredBoundedAndPreserved() {
        // Given the reserved default scope
        // Then it is the lowercase word default
        assertThat(WorkflowScopes.DEFAULT).isEqualTo("default");

        // Given definition keys whose scope is missing, blank, too long or holds a control character
        String longestAllowed = "a".repeat(254) + new String(Character.toChars(0x10348));
        assertThat(longestAllowed.codePointCount(0, longestAllowed.length())).isEqualTo(255);

        // Then a missing scope is rejected as null and the others as invalid arguments
        assertThatThrownBy(() -> new DefinitionKey(null, "orders", 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefinitionKey(" ", "orders", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey("a".repeat(256), "orders", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefinitionKey("a" + (char) 7 + "b", "orders", 1L))
                .isInstanceOf(IllegalArgumentException.class);

        // And a scope of 255 code points that contains a surrogate pair is accepted and read back unchanged
        assertThat(new DefinitionKey(longestAllowed, "orders", 1L).scopeKey()).isEqualTo(longestAllowed);
        assertThat(new DefinitionKey(WorkflowScopes.DEFAULT, "orders", 1L).scopeKey())
                .isEqualTo("default");

        // Given a definition record of kind code
        // Then it is accepted in the default scope and rejected in any other scope
        assertThatCode(() -> codeRecord(WorkflowScopes.DEFAULT)).doesNotThrowAnyException();
        assertThatThrownBy(() -> codeRecord("acme")).isInstanceOf(IllegalArgumentException.class);

        // And a document record is accepted in another scope
        assertThat(documentRecord("acme").key().scopeKey()).isEqualTo("acme");

        // Given definition filters that name no scope, no id and no status
        // Then null means all and is accepted
        DefinitionFilter all = new DefinitionFilter(null, null, null);
        assertThat(all.scopeKey()).isNull();
        assertThat(all.definitionId()).isNull();
        assertThat(all.statuses()).isNull();

        // And a filter keeps the scope, the id and the statuses it was given
        DefinitionFilter narrowed = new DefinitionFilter("acme", "orders", Set.of(DefinitionStatus.CANDIDATE));
        assertThat(narrowed.scopeKey()).isEqualTo("acme");
        assertThat(narrowed.definitionId()).isEqualTo("orders");
        assertThat(narrowed.statuses()).containsExactly(DefinitionStatus.CANDIDATE);

        // Given instances built with the previous 18-argument constructor and with explicit scopes
        WorkflowInstance previousInstance = instanceWithoutScopes();
        WorkflowInstance scopedInstance = instanceInScopes("acme", "globex");

        // Then the previous constructor yields the default scope twice, never null, and the scopes take part in
        // equality
        assertThat(previousInstance.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(previousInstance.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(scopedInstance.scopeKey()).isEqualTo("acme");
        assertThat(scopedInstance.definitionScopeKey()).isEqualTo("globex");
        assertThat(scopedInstance).isNotEqualTo(previousInstance);
        assertThat(previousInstance).isEqualTo(instanceInScopes(WorkflowScopes.DEFAULT, WorkflowScopes.DEFAULT));
        assertThat(instanceInScopes("acme", WorkflowScopes.DEFAULT)).isNotEqualTo(instanceInScopes("acme", "globex"));

        // When every instance updater is applied
        // Then both scopes are unchanged by each, for a scoped instance and for one built without scopes
        for (WorkflowInstance copy : List.of(
                scopedInstance.withVersion(2L),
                scopedInstance.withStatus(WorkflowStatus.WAITING),
                scopedInstance.withCurrentStepId("next"),
                scopedInstance.withWait(WaitType.SIGNAL, "key", UUID.randomUUID()),
                scopedInstance.withWait(WaitType.SIGNAL, "key"),
                scopedInstance.withState("{\"a\":1}"),
                scopedInstance.withError("type", "message"),
                scopedInstance.withUpdatedAt(T1))) {
            assertThat(copy.scopeKey()).isEqualTo("acme");
            assertThat(copy.definitionScopeKey()).isEqualTo("globex");
        }
        for (WorkflowInstance copy : List.of(
                previousInstance.withVersion(2L),
                previousInstance.withStatus(WorkflowStatus.WAITING),
                previousInstance.withCurrentStepId("next"),
                previousInstance.withWait(WaitType.SIGNAL, "key", UUID.randomUUID()),
                previousInstance.withWait(WaitType.SIGNAL, "key"),
                previousInstance.withState("{\"a\":1}"),
                previousInstance.withError("type", "message"),
                previousInstance.withUpdatedAt(T1))) {
            assertThat(copy.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(copy.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        }

        // Given task records built with the previous 23-argument constructor and with an explicit scope
        TaskRecord previousTask = taskWithoutScope();
        TaskRecord scopedTask = taskInScope("acme");

        // Then the previous constructor yields the default scope, never null, and the scope takes part in equality
        assertThat(previousTask.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(scopedTask.scopeKey()).isEqualTo("acme");
        assertThat(scopedTask).isNotEqualTo(previousTask);
        assertThat(previousTask).isEqualTo(taskInScope(WorkflowScopes.DEFAULT));

        // Given queries built with the previous 7-argument constructor and with a scope
        WorkflowInstanceQuery previousQuery =
                new WorkflowInstanceQuery(WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", true);
        WorkflowInstanceQuery scopedQuery = previousQuery.withScopeKey("acme");

        // Then the previous constructor and the unconstrained factories carry no scope filter
        assertThat(previousQuery.scopeKey()).isNull();
        assertThat(WorkflowInstanceQuery.none().scopeKey()).isNull();
        assertThat(WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING).scopeKey())
                .isNull();
        assertThat(WorkflowInstanceQuery.byDefinitionId("orders").scopeKey()).isNull();
        assertThat(WorkflowInstanceQuery.bySubject(new WorkflowSubjectRef("order", "o-1", null))
                        .scopeKey())
                .isNull();
        assertThat(WorkflowInstanceQuery.bySubjectType("order").scopeKey()).isNull();

        // And withScopeKey sets only the scope, and equality includes it
        assertThat(scopedQuery.scopeKey()).isEqualTo("acme");
        assertThat(scopedQuery)
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", true, "acme"));
        assertThat(scopedQuery).isNotEqualTo(previousQuery);
        assertThat(scopedQuery.withScopeKey(null)).isEqualTo(previousQuery);
        assertThat(scopedQuery.withScopeKey("globex").scopeKey()).isEqualTo("globex");

        // When every other query setter is applied
        // Then the scope is preserved and the changed field is the only difference
        assertThat(scopedQuery.withStatus(WorkflowStatus.WAITING))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.WAITING, "bk", "order", "o-1", "v1", "orders", true, "acme"));
        assertThat(scopedQuery.withBusinessKey("bk2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk2", "order", "o-1", "v1", "orders", true, "acme"));
        assertThat(scopedQuery.withSubjectType("invoice"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "invoice", "o-1", "v1", "orders", true, "acme"));
        assertThat(scopedQuery.withSubjectId("o-2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-2", "v1", "orders", true, "acme"));
        assertThat(scopedQuery.withSubjectVersion("v2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v2", "orders", true, "acme"));
        assertThat(scopedQuery.withDefinitionId("other"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "other", true, "acme"));
        assertThat(scopedQuery.withIncludeArchived(false))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", false, "acme"));

        // Given task filters built with the previous 9-argument constructor and with a scope
        TaskFilter previousFilter =
                new TaskFilter(null, "reviewers", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true);
        TaskFilter scopedFilter = previousFilter.withScopeKey("acme");

        // Then the previous constructor and the empty filter carry no scope filter
        assertThat(previousFilter.scopeKey()).isNull();
        assertThat(TaskFilter.empty().scopeKey()).isNull();

        // And withScopeKey sets only the scope, and equality includes it
        assertThat(scopedFilter.scopeKey()).isEqualTo("acme");
        assertThat(scopedFilter)
                .isEqualTo(
                        new TaskFilter(null, "reviewers", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "acme"));
        assertThat(scopedFilter).isNotEqualTo(previousFilter);
        assertThat(scopedFilter.withScopeKey(null)).isEqualTo(previousFilter);
        assertThat(scopedFilter.withScopeKey("globex").scopeKey()).isEqualTo("globex");

        // When every other filter setter is applied to a scoped filter without an assignee
        // Then the scope is preserved and the changed field is the only difference
        TaskFilter base = new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "acme");
        assertThat(base.withAssigneeUser("alice"))
                .isEqualTo(new TaskFilter("alice", null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "acme"));
        assertThat(base.withAssigneeRole("auditors"))
                .isEqualTo(
                        new TaskFilter(null, "auditors", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "acme"));
        assertThat(base.withAssigneeQueue("inbox"))
                .isEqualTo(new TaskFilter(null, null, "inbox", ID, TaskStatus.OPEN, T1, "order", "o-1", true, "acme"));
        assertThat(TaskFilter.empty().withScopeKey("acme").withWorkflowId(ID))
                .isEqualTo(new TaskFilter(null, null, null, ID, null, null, null, null, false, "acme"));
        assertThat(base.withStatus(TaskStatus.COMPLETED))
                .isEqualTo(
                        new TaskFilter(null, null, null, ID, TaskStatus.COMPLETED, T1, "order", "o-1", true, "acme"));
        assertThat(base.withDueBefore(T0))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T0, "order", "o-1", true, "acme"));
        assertThat(base.withSubjectType("invoice"))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "invoice", "o-1", true, "acme"));
        assertThat(base.withSubjectId("o-2"))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-2", true, "acme"));
        assertThat(base.withIncludeArchived(false))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", false, "acme"));

        // And the assignee exclusivity check is unchanged and does not depend on the scope
        assertThatThrownBy(
                        () -> new TaskFilter("alice", "reviewers", null, null, null, null, null, null, false, "acme"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scopedFilter.withAssigneeUser("alice")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- Sample builders ---

    private static WorkflowDefinitionRecord codeRecord(String scopeKey) {
        return definitionRecord(scopeKey, DefinitionKind.CODE, null);
    }

    private static WorkflowDefinitionRecord documentRecord(String scopeKey) {
        return definitionRecord(scopeKey, DefinitionKind.DOCUMENT, "YAML");
    }

    private static WorkflowDefinitionRecord definitionRecord(
            String scopeKey, DefinitionKind kind, String documentFormat) {
        return new WorkflowDefinitionRecord(
                new DefinitionKey(scopeKey, "orders", 1L),
                kind,
                "example.OrdersContract",
                "example.OrdersState",
                PLAN_HASH,
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance("test", null, null, null, null, T0),
                documentFormat,
                T0,
                T0);
    }

    private static WorkflowInstance instanceWithoutScopes() {
        return new WorkflowInstance(
                ID,
                "orders",
                1L,
                "hash",
                0L,
                WorkflowStatus.RUNNING,
                "bk",
                new WorkflowSubjectRef("order", "o-1", "v1"),
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                T0,
                T0,
                null);
    }

    private static WorkflowInstance instanceInScopes(String scopeKey, String definitionScopeKey) {
        return new WorkflowInstance(
                ID,
                "orders",
                1L,
                "hash",
                0L,
                WorkflowStatus.RUNNING,
                "bk",
                new WorkflowSubjectRef("order", "o-1", "v1"),
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                T0,
                T0,
                null,
                scopeKey,
                definitionScopeKey);
    }

    private static TaskRecord taskWithoutScope() {
        return new TaskRecord(
                TASK_ID,
                ID,
                "review",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                decisions(),
                null,
                null,
                null,
                null,
                null,
                T0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static TaskRecord taskInScope(String scopeKey) {
        return new TaskRecord(
                TASK_ID,
                ID,
                "review",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                decisions(),
                null,
                null,
                null,
                null,
                null,
                T0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                scopeKey);
    }

    private static List<TaskDecisionDescriptor> decisions() {
        return List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "next"));
    }
}
