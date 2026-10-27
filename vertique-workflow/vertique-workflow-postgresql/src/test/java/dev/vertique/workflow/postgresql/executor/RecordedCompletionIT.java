// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowTaskNotWaitingException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Payloads;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.State;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that completing a human task is recorded behind a task reservation: the caller's transaction validates from
 * the task row alone, reserves the task, records a completion command and returns; the executor applies it.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordedCompletionIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_completion_it")
            .withMigration("classpath:db/migration/workflow");

    private static final WorkflowActor ACTOR = new WorkflowActor.User("reviewer");

    @Test
    @DisplayName("an unoffered decision or a schema-violating payload is refused from the snapshot without a plan")
    void invalidDecisionOrPayloadIsRefusedWithoutAPlan() {
        try (TaskFixture t = new TaskFixture()) {
            // Given an open task offering approve with schema {amount: integer}, and a recording engine that fails any
            // plan resolution
            PlanlessRegistry planless = new PlanlessRegistry(t.f.registry);
            Node executor = t.f.node("executor").build();
            Node recorder =
                    t.f.node("recorder").withRegistry(planless.registry()).build();
            OpenTask task = t.openTask(executor, "invalid", Schemas.amountInteger());
            Counts before = t.counts(task.id());
            planless.arm();

            // When the caller completes with an unoffered decision, then with approve and a mistyped amount
            Throwable unknown = failureOf(
                    t.completeAsync(recorder, task, "escalate", new JsonObject().put("amount", 1), "k1", null), IDLE);
            Throwable mistyped = failureOf(
                    t.completeAsync(recorder, task, "approve", new JsonObject().put("amount", "x"), "k2", null), IDLE);

            // Then the first is the unknown-decision refusal, the second a payload refusal, and nothing was recorded
            // (the unknown-decision refusal states its reason in its violation; its message is the generic one)
            assertThat(unknown).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(((WorkflowInvalidPayloadException) unknown).violations())
                    .extracting(ViolationDetail::message)
                    .anyMatch(message -> message.contains("decision is not declared"));
            assertThat(mistyped).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(((WorkflowInvalidPayloadException) mistyped).violations())
                    .extracting(ViolationDetail::message)
                    .noneMatch(message -> message.contains("decision is not declared"));
            assertThat(t.completions(task.id())).isEmpty();
            assertThat(t.task(task).status()).isEqualTo("OPEN");
            assertThat(t.counts(task.id())).isEqualTo(before);
            assertThat(planless.resolves()).isZero();
        }
    }

    @Test
    @DisplayName("two concurrent completions reserve the task once and the second is refused as not waiting")
    void concurrentCompletionsReserveTheTaskOnce() {
        try (TaskFixture t = new TaskFixture()) {
            Node node = t.f.node("engine").build();
            OpenTask task = t.openTask(node, "concurrent", Schemas.lenient());
            Counts before = t.counts(task.id());
            try (TwoSessions sessions = TwoSessions.open(node)) {
                // When session A completes with k1 and pauses before commit, and session B completes with k2
                TaskMutationResult recorded = await(t.f.caller(() -> node.engine.taskCompleted(
                        command(task, "approve", new JsonObject().put("note", "first"), "k1", null), sessions.a)));
                assertThat(recorded).isNotNull();
                Future<TaskMutationResult> second = t.f.caller(() -> node.engine.taskCompleted(
                        command(task, "approve", new JsonObject().put("note", "second"), "k2", null), sessions.b));
                // Then B blocks on the execution lock that A holds
                awaitCondition(
                        "B to wait for the execution lock", IDLE, () -> t.f.fixture.ungrantedAdvisoryLocks() == 1);
                assertThat(second.isComplete()).isFalse();
                // And once A commits B fails as not waiting
                sessions.commitA();
                assertThat(failureOf(second, IDLE)).isInstanceOf(WorkflowTaskNotWaitingException.class);
                sessions.rollbackB();
            }
            // And exactly one completion command was recorded, owning the reservation of the COMPLETING task
            List<CommandRow> completions = t.completions(task.id());
            assertThat(completions).singleElement().satisfies(row -> assertThat(row.state())
                    .isEqualTo("PENDING"));
            TaskRow row = t.task(task);
            assertThat(row.status()).isEqualTo("COMPLETING");
            assertThat(row.reservationCommandId())
                    .isEqualTo(completions.getFirst().id());
            assertThat(t.counts(task.id()).dedup()).isEqualTo(before.dedup() + 1);
        }
    }

    @Test
    @DisplayName("a throwing applicator rejects the completion with a sanitized reason and reopens the task")
    void rejectedCompletionReopensTheTask() {
        try (TaskFixture t = new TaskFixture()) {
            Node node = t.f.node("engine").build();
            OpenTask task = t.openTask(node, "rejected", Schemas.lenient());
            JsonObject instanceBefore = t.f.snapshot(task.id());
            JsonObject failing = new JsonObject().put("fail", true).put("note", "n-secret");

            // When the caller completes with a payload that makes the applicator throw, and the executor runs it
            t.complete(node, task, "approve", failing, "k1", null);
            t.f.idle(node, task.id());

            // Then the call succeeded, its command is rejected with a sanitized reason and the task is open again
            CommandRow rejected = t.completions(task.id()).getFirst();
            assertThat(rejected.state()).isEqualTo("REJECTED");
            assertThat(rejected.lastError())
                    .startsWith("DETERMINISTIC_FAILURE")
                    .doesNotContain("n-secret", "applicator echoed", "IllegalStateException");
            History logged = t.f.entry(task.id(), "COMMAND_REJECTED", rejected.id());
            assertThat(logged.reason())
                    .startsWith("DETERMINISTIC_FAILURE")
                    .doesNotContain("n-secret", "applicator echoed", "IllegalStateException");
            assertThat(logged.text()).doesNotContain("n-secret", "applicator echoed", "IllegalStateException");
            TaskRow reopened = t.task(task);
            assertThat(reopened.status()).isEqualTo("OPEN");
            assertThat(reopened.reservationCommandId()).isNull();
            assertThat(t.f.snapshot(task.id())).isEqualTo(instanceBefore);

            // When the caller completes again with a fresh key and a payload the applicator accepts
            t.complete(node, task, "approve", new JsonObject().put("fail", false), "k2", null);
            t.f.idle(node, task.id());

            // Then it applies: the command completed, the task is completed and the instance is past the task
            List<CommandRow> completions = t.completions(task.id());
            assertThat(completions).extracting(CommandRow::state).containsExactly("REJECTED", "COMPLETE");
            assertThat(t.task(task).status()).isEqualTo("COMPLETED");
            assertThat(t.f.snapshot(task.id()).getString("status")).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("version stability is checked from the task row, before any plan is resolved")
    void versionStabilityIsCheckedFromTheTaskRow() {
        try (TaskFixture t = new TaskFixture()) {
            // Given a stability-required task created at subject version v3 and a recording engine without plans
            Definition stable = new ReviewFixture("recorded-review-stable", true);
            t.f.seed(stable, List.of(), new JsonObject());
            PlanlessRegistry planless = new PlanlessRegistry(t.f.registry);
            Node executor = t.f.node("executor").build();
            Node recorder =
                    t.f.node("recorder").withRegistry(planless.registry()).build();
            OpenTask task = t.openTask(executor, stable, "stable", Schemas.lenient(), "v3");
            Counts before = t.counts(task.id());
            planless.arm();

            // When the caller completes with reviewed version v2, and again without any reviewed version
            Throwable different =
                    failureOf(t.completeAsync(recorder, task, "approve", new JsonObject(), "k1", "v2"), IDLE);
            Throwable missing =
                    failureOf(t.completeAsync(recorder, task, "approve", new JsonObject(), "k2", null), IDLE);

            // Then the row records the requirement, both are refused in the caller's transaction and nothing is
            // recorded
            assertThat(t.task(task).requireVersionStability()).isTrue();
            assertThat(different).isInstanceOf(WorkflowStaleSubjectVersionException.class);
            assertThat(missing).isInstanceOf(WorkflowStaleSubjectVersionException.class);
            assertThat(t.completions(task.id())).isEmpty();
            assertThat(t.task(task).status()).isEqualTo("OPEN");
            assertThat(t.counts(task.id())).isEqualTo(before);
            assertThat(planless.resolves()).isZero();
        }
    }

    @Test
    @DisplayName("a signal and a cancel in one transaction race a timed-wait segment without deadlock")
    void signalThenCancelRacingATimedWaitSegmentNeverDeadlocks() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new TimedWaitFixture(), List.of("paid", "note"))) {
            // Given an executor whose segment, on the paid signal, enters a task wait with a due date (a timer row)
            Node node = f.node("engine").recorders(f.intents).build();
            node.startExecutor();
            try {
                for (int run = 0; run < 20; run++) {
                    int repetition = run;
                    WorkflowInstanceId id = await(f.caller(() ->
                            node.pool.withTransaction(tx -> node.engine.start(f.start("race-" + repetition), tx))));
                    node.awaitIdle(id);
                    f.record(node, id, "paid", RecordedSignalIT.signal("paid"), "paid");
                    sleep((repetition * 7L) % 40);
                    String noteKey = "note-" + repetition;

                    // When one caller transaction records a signal and then cancels, as the executor runs that segment
                    await(f.caller(() -> node.pool.withTransaction(tx -> node.engine
                            .signal(id, "note", RecordedSignalIT.signal("note"), noteKey, tx)
                            .compose(v -> node.engine.cancel(id, "race", tx)))));
                    node.awaitIdle(id);

                    // Then both committed in one order or the other: cancelled, nothing in flight, no retried failure
                    assertThat(f.snapshot(id).getString("status"))
                            .as("repetition %s", repetition)
                            .isEqualTo("CANCELLED");
                    assertThat(f.fixture.commandsOf(id))
                            .as("repetition %s", repetition)
                            .allSatisfy(row -> {
                                assertThat(row.transientCount()).isZero();
                                assertThat(row.state())
                                        .isNotIn("PENDING", "DEFERRED", "CLAIMED", "PARKED", "DEAD", "FAILED");
                            });
                }
            } finally {
                node.stopExecutor();
            }
        }
    }

    @Test
    @DisplayName("a legacy snapshot is validated by the executor and a reserved task refuses reassignment")
    void legacySnapshotIsValidatedByTheExecutorAndReservedTaskRefusesReassignment() {
        try (TaskFixture t = new TaskFixture()) {
            Node node = t.f.node("engine").build();
            // Given task L created before schemas and the stability column were stored, and a second open task R
            OpenTask legacy = t.openTask(node, "legacy", Schemas.lenient());
            t.makeLegacy(legacy);
            OpenTask reserved = t.openTask(node, "reserved", Schemas.lenient());
            assertThat(t.task(legacy).requireVersionStability()).isNull();

            // When the caller completes L with a payload violating the plan's decision type and the executor runs it
            t.complete(node, legacy, "approve", new JsonObject().put("amount", "x"), "k1", null);
            assertThat(t.completions(legacy.id())).singleElement().satisfies(row -> assertThat(row.state())
                    .isEqualTo("PENDING"));
            t.f.idle(node, legacy.id());

            // Then L's completion was recorded and then rejected with a logged reason, and L is open again
            CommandRow rejected = t.completions(legacy.id()).getFirst();
            assertThat(rejected.state()).isEqualTo("REJECTED");
            assertThat(t.f.entry(legacy.id(), "COMMAND_REJECTED", rejected.id()).reason())
                    .startsWith("DETERMINISTIC_FAILURE");
            assertThat(t.task(legacy).status()).isEqualTo("OPEN");

            // When R is reserved by a recorded completion and the caller reassigns it
            t.complete(node, reserved, "approve", new JsonObject(), "k2", null);
            assertThat(t.task(reserved).status()).isEqualTo("COMPLETING");
            Throwable reassignment = failureOf(
                    t.f.caller(() -> node.pool.withTransaction(tx -> node.engine.taskReassigned(
                            new TaskReassignmentCommand(
                                    reserved.taskId(),
                                    new TaskAssignment.Role("legal"),
                                    new WorkflowActor.User("manager"),
                                    "re-1",
                                    "specialist needed"),
                            tx))),
                    IDLE);

            // Then the reassignment is refused as not waiting and the assignment is unchanged
            assertThat(reassignment).isInstanceOf(WorkflowTaskNotWaitingException.class);
            assertThat(t.task(reserved).assigneeKey()).isEqualTo("compliance");
            assertThat(t.task(reserved).status()).isEqualTo("COMPLETING");
        }
    }

    @Test
    @DisplayName("payload size, depth and schema are checked before the execution lock is taken")
    void payloadBoundsAndSchemaAreCheckedBeforeTheLock() {
        try (TaskFixture t = new TaskFixture()) {
            // Given a recording bound of 1024 bytes and an open task offering approve with schema {amount: integer}
            Node executor = t.f.node("executor").build();
            Node recorder = t.f.node("recorder")
                    .recordingConfig(RecordedSignalIT.recording(100, 1024))
                    .build();
            OpenTask task = t.openTask(executor, "bounds", Schemas.amountInteger());
            Counts before = t.counts(task.id());
            // And another session holds the instance's execution lock throughout; no lock timeout is installed
            try (ExecutorFixture.HeldLock held = t.f.fixture.holdExecutionLock(task.id())) {
                assertThat(t.f.fixture.holdersOfX(task.id())).hasSize(1);

                // When the caller completes with a 2 KiB payload, a payload nested 65 levels, and a mistyped amount
                Throwable large = failureOf(
                        t.completeAsync(recorder, task, "approve", Payloads.ofSize(2048), "k1", null),
                        Duration.ofSeconds(2));
                Throwable deep = failureOf(
                        t.completeAsync(recorder, task, "approve", Payloads.nested(65), "k2", null),
                        Duration.ofSeconds(2));
                Throwable mistyped = failureOf(
                        t.completeAsync(recorder, task, "approve", new JsonObject().put("amount", "x"), "k3", null),
                        Duration.ofSeconds(2));

                // Then each fails at once while the lock is still held, and nothing is recorded
                assertThat(large).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(deep).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(mistyped).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(t.f.fixture.holdersOfX(task.id())).hasSize(1);
                assertThat(t.f.fixture.ungrantedAdvisoryLocks()).isZero();
                assertThat(t.completions(task.id())).isEmpty();
                assertThat(t.task(task).status()).isEqualTo("OPEN");
                assertThat(t.counts(task.id())).isEqualTo(before);
            }
        }
    }

    @Test
    @DisplayName("a completion overrunning the step cap is rejected with STEP_LIMIT and its task reopens")
    void completionOverrunningTheStepCapIsRejectedWithStepLimit() {
        try (TaskFixture t = new TaskFixture()) {
            // Given a cap of 50 nodes per segment and an open task whose approve routes a spin payload into 60 nodes
            Node node = t.f.node("engine").maxStepsPerSegment(50).build();
            OpenTask task = t.openTask(node, "spin", Schemas.lenient());
            JsonObject instanceBefore = t.f.snapshot(task.id());

            // When the caller completes with the spin payload and the executor runs it
            t.complete(node, task, "approve", new JsonObject().put("spin", true), "k1", null);
            t.f.idle(node, task.id());

            // Then the command is rejected with STEP_LIMIT, the task is open and the instance never moved or failed
            CommandRow overrun = t.completions(task.id()).getFirst();
            assertThat(overrun.state()).isEqualTo("REJECTED");
            assertThat(overrun.lastError()).startsWith("STEP_LIMIT");
            assertThat(t.f.entry(task.id(), "COMMAND_REJECTED", overrun.id()).reason())
                    .startsWith("STEP_LIMIT");
            TaskRow reopened = t.task(task);
            assertThat(reopened.status()).isEqualTo("OPEN");
            assertThat(reopened.reservationCommandId()).isNull();
            assertThat(t.f.snapshot(task.id())).isEqualTo(instanceBefore);
            assertThat(t.f.snapshot(task.id()).getString("status")).isNotEqualTo("FAILED");

            // When the caller completes again without spinning
            t.complete(node, task, "approve", new JsonObject().put("spin", false), "k2", null);
            t.f.idle(node, task.id());

            // Then that completion applies
            assertThat(t.completions(task.id())).extracting(CommandRow::state).containsExactly("REJECTED", "COMPLETE");
            assertThat(t.task(task).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a committed duplicate replays its result before any payload check, even after the bound shrank")
    void committedDuplicateReplaysBeforeValidationAfterTheBoundShrinks() {
        try (TaskFixture t = new TaskFixture()) {
            // Given engine A with a 4096-byte bound, a task with schema {note: string}, and a recorded and applied
            // completion with a 2 KiB note under k1
            Node a = t.f.node("engine-a")
                    .recordingConfig(RecordedSignalIT.recording(100, 4096))
                    .build();
            OpenTask task = t.openTask(a, "replay", Schemas.noteString());
            JsonObject original = new JsonObject().put("note", "x".repeat(2048));
            TaskMutationResult first = t.complete(a, task, "approve", original, "k1", null);
            t.f.idle(a, task.id());
            assertThat(t.task(task).status()).isEqualTo("COMPLETED");
            // And engine B on the same database with a 1024-byte bound
            Node b = t.f.node("engine-b")
                    .recordingConfig(RecordedSignalIT.recording(100, 1024))
                    .build();
            Counts settled = t.counts(task.id());

            // When B repeats the completion under k1 while another session holds the execution lock
            TaskMutationResult replay;
            try (ExecutorFixture.HeldLock held = t.f.fixture.holdExecutionLock(task.id())) {
                replay = await(t.completeAsync(b, task, "approve", original, "k1", null), Duration.ofSeconds(2));
            }
            // Then the replay succeeded without waiting for the lock and recorded nothing
            assertThat(first).isNotNull();
            assertThat(replay).isNotNull();
            assertThat(t.counts(task.id())).isEqualTo(settled);

            // When B completes under k1 with another, small payload
            Throwable conflict = failureOf(
                    t.completeAsync(b, task, "approve", new JsonObject().put("note", "y".repeat(100)), "k1", null),
                    IDLE);
            // Then it is refused as an idempotency conflict, under the lock, recording nothing
            assertThat(conflict).isInstanceOf(WorkflowIdempotencyConflictException.class);
            assertThat(t.counts(task.id())).isEqualTo(settled);

            // When B completes under a new key with the original 2 KiB payload
            Throwable oversized = failureOf(t.completeAsync(b, task, "approve", original, "k2", null), IDLE);
            // Then the new request is checked against the current bound and refused, recording nothing
            assertThat(oversized).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(t.counts(task.id())).isEqualTo(settled);
        }
    }

    // --- helpers ---

    static TaskCompletionCommand command(
            OpenTask task, String decision, Object payload, String key, @Nullable String reviewedVersion) {
        return new TaskCompletionCommand(task.taskId(), decision, payload, key, ACTOR, reviewedVersion);
    }

    /** The row counts that a refusal or a replay must leave unchanged. */
    record Counts(long commands, long dedup, long history) {}

    record OpenTask(WorkflowInstanceId id, UUID taskId) {}

    record TaskRow(
            String status,
            @Nullable Long reservationCommandId,
            @Nullable Boolean requireVersionStability,
            String assigneeKey,
            JsonArray decisions) {}

    /** The JSON Schemas the fixture stores in a task's decision snapshot. */
    static final class Schemas {
        static JsonObject lenient() {
            return new JsonObject().put("type", "object");
        }

        static JsonObject amountInteger() {
            return new JsonObject()
                    .put("type", "object")
                    .put("properties", new JsonObject().put("amount", new JsonObject().put("type", "integer")));
        }

        static JsonObject noteString() {
            return new JsonObject()
                    .put("type", "object")
                    .put("properties", new JsonObject().put("note", new JsonObject().put("type", "string")));
        }
    }

    /** The decision payload of the fixture tasks. */
    record Decision(
            @Nullable String note,
            boolean fail,
            boolean spin,
            @Nullable Integer amount) {}

    /** A task wait whose approve applicator can throw or route into a loop longer than a small step cap. */
    static final class ReviewFixture extends Definition {
        ReviewFixture() {
            this("recorded-review", false);
        }

        ReviewFixture(String id, boolean requireStability) {
            super(id, wf -> {
                wf.initialStep("review");
                var task = wf.task("review").assignToRole("compliance");
                if (requireStability) {
                    task = task.requireVersionStability();
                }
                task.decision("approve", Decision.class)
                        .onDecision((state, decision) -> {
                            if (decision.fail()) {
                                throw new IllegalStateException("applicator echoed " + decision.note());
                            }
                            return new State(state.ref(), List.of("approve"), decision.spin(), false);
                        })
                        .toStep("route")
                        .build();
                wf.decide("route", state -> state.spin() ? "spin-0" : "done");
                ParkedSignalIT.LoopFixture.pathOf(wf, "spin", 60, "spin-end", () -> {});
                wf.complete("spin-end");
                wf.complete("done");
            });
        }
    }

    /** A signal wait followed by a task wait with a due date, so entering the task wait writes a timer row. */
    static final class TimedWaitFixture extends Definition {
        TimedWaitFixture() {
            super("recorded-timed-wait", wf -> {
                wf.initialStep("go");
                RecordedSignalIT.waitFor(wf, "go", "paid", "review");
                wf.task("review")
                        .assignToRole("compliance")
                        .dueIn(Duration.ofHours(1))
                        .onDue(state -> state)
                        .toStepOnDue("done")
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> state)
                        .toStep("done")
                        .build();
                wf.complete("done");
            });
        }
    }

    /** Wraps a registry and, once armed, fails every plan resolution and counts it. */
    static final class PlanlessRegistry {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger resolves = new AtomicInteger();
        private final WorkflowRegistry registry;

        PlanlessRegistry(WorkflowRegistry real) {
            this.registry = (WorkflowRegistry) Proxy.newProxyInstance(
                    WorkflowRegistry.class.getClassLoader(),
                    new Class<?>[] {WorkflowRegistry.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("resolve") && armed.get()) {
                            resolves.incrementAndGet();
                            throw new IllegalStateException("no plan may be resolved while recording a completion");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        WorkflowRegistry registry() {
            return registry;
        }

        void arm() {
            armed.set(true);
        }

        int resolves() {
            return resolves.get();
        }
    }

    /** Two read-committed sessions on the node's pool, each in a transaction of its own. */
    static final class TwoSessions implements AutoCloseable {
        final SqlConnection a;
        final SqlConnection b;
        private final Transaction aTx;
        private final Transaction bTx;
        private boolean aOpen;
        private boolean bOpen;

        private TwoSessions(Node node) {
            a = await(node.pool.getConnection());
            SqlConnection second = null;
            Transaction first = null;
            try {
                second = await(node.pool.getConnection());
                first = await(a.begin());
                aOpen = true;
                bTx = await(second.begin());
                bOpen = true;
            } catch (RuntimeException | AssertionError e) {
                if (aOpen) {
                    await(first.rollback());
                }
                if (second != null) {
                    await(second.close());
                }
                await(a.close());
                throw e;
            }
            aTx = first;
            b = second;
        }

        static TwoSessions open(Node node) {
            return new TwoSessions(node);
        }

        void commitA() {
            await(aTx.commit());
            aOpen = false;
        }

        void rollbackB() {
            await(bTx.rollback());
            bOpen = false;
        }

        @Override
        public void close() {
            try {
                try {
                    if (aOpen) await(aTx.rollback());
                } finally {
                    if (bOpen) await(bTx.rollback());
                }
            } finally {
                try {
                    await(a.close());
                } finally {
                    await(b.close());
                }
            }
        }
    }

    /** Opens fixture tasks, completes them through the recording engine and observes the rows. */
    static final class TaskFixture implements AutoCloseable {
        final SignalFixture f;

        TaskFixture() {
            this.f = new SignalFixture(db.toPoolConfig(), new ReviewFixture(), List.of());
        }

        /** Starts the standard definition, runs it to its task wait and stores the schema in the task's snapshot. */
        OpenTask openTask(Node executor, String ref, JsonObject schema) {
            return openTask(executor, f.definition, ref, schema, null);
        }

        OpenTask openTask(
                Node executor, Definition code, String ref, JsonObject schema, @Nullable String subjectVersion) {
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
            WorkflowInstanceId id =
                    await(f.caller(() -> executor.pool.withTransaction(tx -> executor.engine.start(recorded, tx))));
            f.idle(executor, id);
            Row row = row("SELECT task_id FROM workflow_tasks WHERE workflow_id=$1", Tuple.of(id.value()));
            OpenTask task = new OpenTask(id, row.getUUID("task_id"));
            JsonArray decisions = decisions(task);
            for (int i = 0; i < decisions.size(); i++) {
                decisions.getJsonObject(i).put("payloadSchema", schema.copy());
            }
            setDecisions(task, decisions);
            return task;
        }

        /** Rewrites the task as one created before decision schemas and the stability column were stored. */
        void makeLegacy(OpenTask task) {
            JsonArray decisions = decisions(task);
            for (int i = 0; i < decisions.size(); i++) {
                decisions.getJsonObject(i).remove("payloadSchema");
            }
            setDecisions(task, decisions);
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_tasks SET require_version_stability = NULL WHERE task_id=$1")
                    .execute(Tuple.of(task.taskId())));
        }

        private JsonArray decisions(OpenTask task) {
            return task(task).decisions();
        }

        private void setDecisions(OpenTask task, JsonArray decisions) {
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_tasks SET decisions_snapshot_json=$2 WHERE task_id=$1")
                    .execute(Tuple.of(task.taskId(), decisions)));
        }

        Future<TaskMutationResult> completeAsync(
                Node node,
                OpenTask task,
                String decision,
                Object payload,
                String key,
                @Nullable String reviewedVersion) {
            return f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.taskCompleted(command(task, decision, payload, key, reviewedVersion), tx)));
        }

        TaskMutationResult complete(
                Node node,
                OpenTask task,
                String decision,
                Object payload,
                String key,
                @Nullable String reviewedVersion) {
            return await(completeAsync(node, task, decision, payload, key, reviewedVersion));
        }

        List<CommandRow> completions(WorkflowInstanceId id) {
            return f.fixture.commandsOf(id).stream()
                    .filter(row -> row.kind().equals("COMPLETION"))
                    .toList();
        }

        TaskRow task(OpenTask task) {
            Row row = row(
                    "SELECT status, reservation_command_id, require_version_stability, assignee_key,"
                            + " decisions_snapshot_json FROM workflow_tasks WHERE task_id=$1",
                    Tuple.of(task.taskId()));
            return new TaskRow(
                    row.getString("status"),
                    row.getLong("reservation_command_id"),
                    row.getBoolean("require_version_stability"),
                    row.getString("assignee_key"),
                    row.getJsonArray("decisions_snapshot_json"));
        }

        Counts counts(WorkflowInstanceId id) {
            return new Counts(
                    f.fixture.rowsOf("workflow_commands", id),
                    f.fixture.rowsOf("workflow_dedup", id),
                    f.fixture.rowsOf("workflow_history", id));
        }

        private Row row(String sql, Tuple tuple) {
            return await(f.fixture.observer().preparedQuery(sql).execute(tuple))
                    .iterator()
                    .next();
        }

        @Override
        public void close() {
            f.close();
        }
    }
}
