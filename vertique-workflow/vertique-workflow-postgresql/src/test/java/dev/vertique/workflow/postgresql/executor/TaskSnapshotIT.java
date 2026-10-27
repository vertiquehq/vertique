// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.Decision;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.ReviewFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what a newly created human task stores: the payload schema of each decision in its decision snapshot, when a
 * schema producer is installed, and the plan's version-stability requirement in its own column.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TaskSnapshotIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_task_snapshot_it")
            .withMigration("classpath:db/migration/workflow");

    private static final JsonObject SCHEMA = new JsonObject()
            .put("type", "object")
            .put("properties", new JsonObject().put("note", new JsonObject().put("type", "string")));

    @Test
    @DisplayName("a new task stores each decision's payload schema in its snapshot and its stability requirement")
    void newTaskStoresDecisionSchemasAndStabilityRequirement() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new ReviewFixture("snapshot-single", true), List.of())) {
            // Given an engine whose schema producer describes the approve decision of the review step
            Node node = f.node("engine")
                    .schemaProducer(new FixedSchemas("review", "approve", SCHEMA))
                    .build();

            // When an instance reaches a stability-required task
            WorkflowInstanceId id = startAtTask(f, node, f.definition, "single", "v3");

            // Then the snapshot of the task holds the schema of the decision and the row records the requirement
            Row task = taskOf(f, id);
            assertThat(task.getBoolean("require_version_stability")).isTrue();
            JsonArray decisions = task.getJsonArray("decisions_snapshot_json");
            assertThat(decisions).hasSize(1);
            assertThat(decisions.getJsonObject(0).getString("name")).isEqualTo("approve");
            assertThat(decisions.getJsonObject(0).getJsonObject("payloadSchema"))
                    .isEqualTo(SCHEMA);
        }
    }

    @Test
    @DisplayName("a task created without a schema producer stores no schema and records that stability is not required")
    void taskWithoutProducerStoresNoSchema() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new ReviewFixture("snapshot-plain", false), List.of())) {
            // Given an engine without a schema producer
            Node node = f.node("engine").build();

            // When an instance reaches a task that does not require stability
            WorkflowInstanceId id = startAtTask(f, node, f.definition, "plain", null);

            // Then the snapshot carries no schema and the row says stability is not required
            Row task = taskOf(f, id);
            assertThat(task.getBoolean("require_version_stability")).isFalse();
            assertThat(task.getJsonArray("decisions_snapshot_json")
                            .getJsonObject(0)
                            .containsKey("payloadSchema"))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a schema producer that fails does not fail the creation of the task")
    void failingProducerDoesNotFailTaskCreation() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new ReviewFixture("snapshot-failing", false), List.of())) {
            // Given a producer that cannot describe the plan
            WorkflowPlanSchemaProducer failing = new WorkflowPlanSchemaProducer() {
                @Override
                public JsonObject startSchema(RuntimeWorkflow plan) {
                    return null;
                }

                @Override
                public Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan) {
                    throw new IllegalStateException("no schemas");
                }
            };
            Node node = f.node("engine").schemaProducer(failing).build();

            // When an instance reaches the task
            WorkflowInstanceId id = startAtTask(f, node, f.definition, "failing", null);

            // Then the task exists, without a schema
            assertThat(taskOf(f, id).getString("status")).isEqualTo("OPEN");
            assertThat(taskOf(f, id)
                            .getJsonArray("decisions_snapshot_json")
                            .getJsonObject(0)
                            .containsKey("payloadSchema"))
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a branch task stores the schema of its decision and its stability requirement as well")
    void branchTaskStoresDecisionSchemasAndStabilityRequirement() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new BranchReviewFixture(), List.of())) {
            // Given an engine whose schema producer describes the approve decision of the branch task
            Node node = f.node("engine")
                    .schemaProducer(new FixedSchemas("task-step", "approve", SCHEMA))
                    .build();

            // When an instance reaches the task inside a branch
            WorkflowInstanceId id = startAtTask(f, node, f.definition, "branch", "v3");

            // Then the branch task's snapshot and row are written like a single-path task's
            Row task = taskOf(f, id);
            assertThat(task.getUUID("branch_token_id")).isNotNull();
            assertThat(task.getBoolean("require_version_stability")).isTrue();
            assertThat(task.getJsonArray("decisions_snapshot_json")
                            .getJsonObject(0)
                            .getJsonObject("payloadSchema"))
                    .isEqualTo(SCHEMA);
        }
    }

    private static WorkflowInstanceId startAtTask(
            SignalFixture f, Node node, Definition code, String ref, @Nullable String subjectVersion) {
        StartCommand start = f.start(code, ref);
        if (subjectVersion != null) {
            start = new StartCommand(
                    start.definitionId(),
                    start.contractName(),
                    start.payload(),
                    start.idempotencyKey(),
                    start.businessKey(),
                    new WorkflowSubjectRef("Article", "art-" + ref, subjectVersion),
                    null,
                    null,
                    null);
        }
        StartCommand recorded = start;
        WorkflowInstanceId id = await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(recorded, tx))));
        f.idle(node, id);
        return id;
    }

    private static Row taskOf(SignalFixture f, WorkflowInstanceId id) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT status, branch_token_id, require_version_stability,"
                                + " decisions_snapshot_json FROM workflow_tasks WHERE workflow_id=$1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
    }

    /** A producer that describes one decision of one step. */
    private record FixedSchemas(String step, String decision, JsonObject schema) implements WorkflowPlanSchemaProducer {

        @Override
        public JsonObject startSchema(RuntimeWorkflow plan) {
            return null;
        }

        @Override
        public Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan) {
            return Map.of(step, Map.of(decision, schema));
        }
    }

    /** A fork whose first branch waits at a stability-required task and whose second branch ends at once. */
    static final class BranchReviewFixture extends Definition {
        BranchReviewFixture() {
            super("branch-review", wf -> {
                wf.fork("fork")
                        .branch("branch-a", "task-step", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("branch-b", "complete-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join")
                        .task("task-step")
                        .assignToRole("reviewers")
                        .requireVersionStability()
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> state)
                        .toStep("complete-a")
                        .build()
                        .complete("complete-a")
                        .complete("complete-b")
                        .join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin()
                        .complete("done");
                wf.initialStep("fork");
            });
        }
    }
}
