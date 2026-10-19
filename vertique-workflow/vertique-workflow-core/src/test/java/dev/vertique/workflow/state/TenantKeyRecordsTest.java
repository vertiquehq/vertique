// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Unit tests for the optional tenant key carried by instances, tasks and their query types. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class TenantKeyRecordsTest {

    private static final WorkflowInstanceId ID =
            new WorkflowInstanceId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant T0 = Instant.parse("2026-05-10T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-10T01:00:00Z");

    @Test
    void previousConstructorsYieldNullTenantAndUpdatersPreserveIt() {
        // Given instances built with the previous 18-argument constructor and with tenant t1
        WorkflowInstance previousInstance = new WorkflowInstance(
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
        WorkflowInstance tenantInstance = new WorkflowInstance(
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
                "t1");

        // Then the previous constructor yields a null tenant, and the tenant takes part in equality
        assertThat(previousInstance.tenantKey()).isNull();
        assertThat(tenantInstance.tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance).isNotEqualTo(previousInstance);
        assertThat(previousInstance)
                .isEqualTo(new WorkflowInstance(
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
                        null));

        // When every instance updater is applied
        // Then the tenant is unchanged by each, for a tenant instance and for an untenanted one
        assertThat(tenantInstance.withVersion(2L).tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance.withStatus(WorkflowStatus.WAITING).tenantKey())
                .isEqualTo("t1");
        assertThat(tenantInstance.withCurrentStepId("next").tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance
                        .withWait(WaitType.SIGNAL, "key", UUID.randomUUID())
                        .tenantKey())
                .isEqualTo("t1");
        assertThat(tenantInstance.withWait(WaitType.SIGNAL, "key").tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance.withState("{\"a\":1}").tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance.withError("type", "message").tenantKey()).isEqualTo("t1");
        assertThat(tenantInstance.withUpdatedAt(T1).tenantKey()).isEqualTo("t1");
        assertThat(previousInstance.withVersion(2L).tenantKey()).isNull();
        assertThat(previousInstance.withStatus(WorkflowStatus.WAITING).tenantKey())
                .isNull();
        assertThat(previousInstance.withCurrentStepId("next").tenantKey()).isNull();
        assertThat(previousInstance.withWait(WaitType.SIGNAL, "key").tenantKey())
                .isNull();
        assertThat(previousInstance.withState("{\"a\":1}").tenantKey()).isNull();
        assertThat(previousInstance.withError("type", "message").tenantKey()).isNull();
        assertThat(previousInstance.withUpdatedAt(T1).tenantKey()).isNull();

        // Given task records built with the previous canonical constructor and with tenant t1
        List<TaskDecisionDescriptor> decisions =
                List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "next"));
        TaskRecord previousTask = new TaskRecord(
                TASK_ID,
                ID,
                "review",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                decisions,
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
        TaskRecord tenantTask = new TaskRecord(
                TASK_ID,
                ID,
                "review",
                new TaskAssignment.Role("reviewers"),
                TaskStatus.OPEN,
                decisions,
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
                "t1");

        // Then the previous constructor yields a null tenant and the tenant takes part in equality
        assertThat(previousTask.tenantKey()).isNull();
        assertThat(tenantTask.tenantKey()).isEqualTo("t1");
        assertThat(tenantTask).isNotEqualTo(previousTask);

        // Given queries built with the previous 7-argument constructor and with tenant t1
        WorkflowInstanceQuery previousQuery =
                new WorkflowInstanceQuery(WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", true);
        WorkflowInstanceQuery tenantQuery = previousQuery.withTenantKey("t1");

        // Then the previous constructor and the unconstrained factories carry no tenant
        assertThat(previousQuery.tenantKey()).isNull();
        assertThat(WorkflowInstanceQuery.none().tenantKey()).isNull();
        assertThat(WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING).tenantKey())
                .isNull();
        assertThat(WorkflowInstanceQuery.byDefinitionId("orders").tenantKey()).isNull();
        assertThat(WorkflowInstanceQuery.bySubject(new WorkflowSubjectRef("order", "o-1", null))
                        .tenantKey())
                .isNull();
        assertThat(WorkflowInstanceQuery.bySubjectType("order").tenantKey()).isNull();

        // And withTenantKey sets only the tenant, and equality includes it
        assertThat(tenantQuery.tenantKey()).isEqualTo("t1");
        assertThat(tenantQuery)
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", true, "t1"));
        assertThat(tenantQuery).isNotEqualTo(previousQuery);
        assertThat(tenantQuery.withTenantKey(null)).isEqualTo(previousQuery);
        assertThat(tenantQuery.withTenantKey("t2").tenantKey()).isEqualTo("t2");
        assertThat(tenantQuery.withTenantKey("t2").withTenantKey("t1")).isEqualTo(tenantQuery);

        // When every other query setter is applied
        // Then the tenant is preserved and the changed field is the only difference
        assertThat(tenantQuery.withStatus(WorkflowStatus.WAITING))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.WAITING, "bk", "order", "o-1", "v1", "orders", true, "t1"));
        assertThat(tenantQuery.withBusinessKey("bk2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk2", "order", "o-1", "v1", "orders", true, "t1"));
        assertThat(tenantQuery.withSubjectType("invoice"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "invoice", "o-1", "v1", "orders", true, "t1"));
        assertThat(tenantQuery.withSubjectId("o-2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-2", "v1", "orders", true, "t1"));
        assertThat(tenantQuery.withSubjectVersion("v2"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v2", "orders", true, "t1"));
        assertThat(tenantQuery.withDefinitionId("other"))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "other", true, "t1"));
        assertThat(tenantQuery.withIncludeArchived(false))
                .isEqualTo(new WorkflowInstanceQuery(
                        WorkflowStatus.RUNNING, "bk", "order", "o-1", "v1", "orders", false, "t1"));

        // Given task filters built with the previous 9-argument constructor and with tenant t1
        TaskFilter previousFilter =
                new TaskFilter(null, "reviewers", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true);
        TaskFilter tenantFilter = previousFilter.withTenantKey("t1");

        // Then the previous constructor and the empty filter carry no tenant
        assertThat(previousFilter.tenantKey()).isNull();
        assertThat(TaskFilter.empty().tenantKey()).isNull();

        // And withTenantKey sets only the tenant, and equality includes it
        assertThat(tenantFilter.tenantKey()).isEqualTo("t1");
        assertThat(tenantFilter)
                .isEqualTo(
                        new TaskFilter(null, "reviewers", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "t1"));
        assertThat(tenantFilter).isNotEqualTo(previousFilter);
        assertThat(tenantFilter.withTenantKey(null)).isEqualTo(previousFilter);
        assertThat(tenantFilter.withTenantKey("t2").tenantKey()).isEqualTo("t2");

        // When every other filter setter is applied to a tenant filter without an assignee
        // Then the tenant is preserved and the changed field is the only difference
        TaskFilter base = new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "t1");
        assertThat(base.withAssigneeUser("alice"))
                .isEqualTo(new TaskFilter("alice", null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "t1"));
        assertThat(base.withAssigneeRole("auditors"))
                .isEqualTo(new TaskFilter(null, "auditors", null, ID, TaskStatus.OPEN, T1, "order", "o-1", true, "t1"));
        assertThat(base.withAssigneeQueue("inbox"))
                .isEqualTo(new TaskFilter(null, null, "inbox", ID, TaskStatus.OPEN, T1, "order", "o-1", true, "t1"));
        assertThat(TaskFilter.empty().withTenantKey("t1").withWorkflowId(ID))
                .isEqualTo(new TaskFilter(null, null, null, ID, null, null, null, null, false, "t1"));
        assertThat(base.withStatus(TaskStatus.COMPLETED))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.COMPLETED, T1, "order", "o-1", true, "t1"));
        assertThat(base.withDueBefore(T0))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T0, "order", "o-1", true, "t1"));
        assertThat(base.withSubjectType("invoice"))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "invoice", "o-1", true, "t1"));
        assertThat(base.withSubjectId("o-2"))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-2", true, "t1"));
        assertThat(base.withIncludeArchived(false))
                .isEqualTo(new TaskFilter(null, null, null, ID, TaskStatus.OPEN, T1, "order", "o-1", false, "t1"));

        // And the assignee exclusivity check is unchanged and does not depend on the tenant
        assertThatThrownBy(() -> new TaskFilter("alice", "reviewers", null, null, null, null, null, null, false, "t1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tenantFilter.withAssigneeUser("alice")).isInstanceOf(IllegalArgumentException.class);
    }
}
