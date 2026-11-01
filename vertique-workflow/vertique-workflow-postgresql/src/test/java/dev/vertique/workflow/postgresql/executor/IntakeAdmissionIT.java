// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.core.exception.UnavailableException;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.intake.IntakeAdmission;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.history.WorkflowHistoryChange;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.DueDateTask;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies, over a real database, what a node that refuses new intake does: it refuses a new start and a new signal
 * after every replay and terminal refusal and before anything is written, it answers a retried request with its
 * original outcome, and it records the work that drains the queue.
 *
 * <p>A switch stands in for the node's admission state, because an engine built outside the dependency graph has no
 * sampler and always admits.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class IntakeAdmissionIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_intake_admission_it")
            .withMigration("classpath:db/migration/workflow");

    private final AtomicBoolean refusing = new AtomicBoolean();
    private final IntakeAdmission admission = () -> !refusing.get();

    private SignalFixture order() {
        return new SignalFixture(db.toPoolConfig(), new RecordedSignalIT.OrderSignalFixture(), List.of("paid", "note"));
    }

    @Test
    @DisplayName("a refused new start and new signal write nothing, and the same requests succeed once the node admits")
    void refusedRequestsWriteNothingAndSucceedLater() {
        try (SignalFixture f = order()) {
            // Given an instance waiting for its signal, and a node that now refuses new intake
            Node node = f.node("engine").intakeAdmission(admission).build();
            WorkflowInstanceId waiting = f.waiting(node, "A");
            Counts before = Counts.of(f, waiting);
            refusing.set(true);

            // When a new start and a new signal arrive
            Throwable start = failureOf(startAsync(f, node, "B"), IDLE);
            Throwable signal =
                    failureOf(f.recordAsync(node, waiting, "paid", RecordedSignalIT.signal("paid"), "k1"), IDLE);

            // Then both fail with the retryable unavailable refusal, which names neither request
            assertThat(start)
                    .isInstanceOf(WorkflowIntakeRefusedException.class)
                    .isInstanceOf(UnavailableException.class)
                    .hasMessageNotContaining("B");
            assertThat(signal).isInstanceOf(WorkflowIntakeRefusedException.class);

            // And no instance, command, history, dedup or open-signal change remains, and the refused keys are free
            assertThat(Counts.of(f, waiting)).isEqualTo(before);
            assertThat(f.dedup(waiting, "k1")).isZero();
            assertThat(startDedupRows(f, "B")).isZero();

            // And the same requests succeed once the node admits
            refusing.set(false);
            WorkflowInstanceId started = await(startAsync(f, node, "B"));
            f.record(node, waiting, "paid", RecordedSignalIT.signal("paid"), "k1");
            assertThat(started).isNotNull();
            assertThat(f.dedup(waiting, "k1")).isEqualTo(1);
            assertThat(f.signal(waiting, "k1").state()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName(
            "a start and a signal that committed before the pressure rose return their original outcome when retried")
    void retriesGetTheirOriginalOutcome() {
        try (SignalFixture f = order()) {
            // Given a start and a signal that committed while the node admitted
            Node node = f.node("engine").intakeAdmission(admission).build();
            WorkflowInstanceId first = await(startAsync(f, node, "replayed"));
            f.record(node, first, "paid", RecordedSignalIT.signal("paid"), "kept");
            Counts before = Counts.of(f, first);

            // When the node refuses and the same start and the same signal are sent again
            refusing.set(true);
            WorkflowInstanceId again = await(startAsync(f, node, "replayed"));
            f.record(node, first, "paid", RecordedSignalIT.signal("paid"), "kept");

            // Then the start answers with the original instance, the signal succeeds, and nothing was added
            assertThat(again).isEqualTo(first);
            assertThat(Counts.of(f, first)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("a missing or ended instance and an invalid request keep their own refusal on a node that refuses")
    void terminalRefusalsKeepTheirOutcome() {
        try (SignalFixture f = order()) {
            // Given an ended instance and a node that refuses new intake
            Node node = f.node("engine").intakeAdmission(admission).build();
            WorkflowInstanceId ended = f.waiting(node, "end-B");
            f.record(node, ended, "paid", RecordedSignalIT.signal("paid"), "k1");
            f.idle(node, ended);
            assertThat(f.snapshot(ended).getString("status")).isEqualTo("COMPLETED");
            refusing.set(true);

            // When a signal addresses a missing instance, an ended one, or carries an oversized name, and a start has
            // a key that is too long
            Throwable missing = failureOf(
                    f.recordAsync(
                            node, new WorkflowInstanceId(UUID.randomUUID()), "paid", RecordedSignalIT.signal("x"), "k"),
                    IDLE);
            Throwable endedInstance =
                    failureOf(f.recordAsync(node, ended, "paid", RecordedSignalIT.signal("x"), "k2"), IDLE);
            Throwable longName =
                    failureOf(f.recordAsync(node, ended, "n".repeat(300), RecordedSignalIT.signal("x"), "k3"), IDLE);
            StartCommand base = f.start("long-key");
            StartCommand longKey = new StartCommand(
                    base.definitionId(),
                    base.contractName(),
                    base.payload(),
                    "k".repeat(300),
                    null,
                    null,
                    null,
                    null,
                    null);
            Throwable start =
                    failureOf(f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(longKey, tx))), IDLE);

            // And a start names a definition that has no current version
            StartCommand unknown = new StartCommand(
                    "no-such-definition", base.contractName(), base.payload(), "unknown", null, null, null, null, null);
            Throwable noVersion =
                    failureOf(f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(unknown, tx))), IDLE);

            // Then each fails with its own refusal and none with the intake refusal
            assertThat(noVersion).isInstanceOf(WorkflowNoCurrentVersionException.class);
            assertThat(missing).isInstanceOf(WorkflowInstanceNotFoundException.class);
            assertThat(endedInstance).isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(longName).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(start).isInstanceOf(WorkflowInvalidPayloadException.class);
        }
    }

    @Test
    @DisplayName(
            "a contract mismatch, an invalid payload and a deprecated version keep their own refusal for a start on a node that refuses")
    void startTerminalRefusalsKeepTheirOutcome() {
        String definitionId = "intake-start-refusals";
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(), new RecordedSignalIT.OrderSignalFixture(definitionId), List.of("paid", "note"))) {
            // Given a node that refuses new intake
            Node node = f.node("engine").intakeAdmission(admission).build();
            refusing.set(true);
            Counts before = Counts.tables(f);
            StartCommand valid = f.start("terminal");

            // When a start names version 1 through a client of a contract the definition does not implement
            Throwable mismatch = failureOf(
                    f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(
                            new StartCommand(
                                    valid.definitionId(),
                                    "not.the.Contract",
                                    valid.payload(),
                                    "mismatch",
                                    null,
                                    null,
                                    1L,
                                    null,
                                    null),
                            tx))),
                    IDLE);

            // And a start carries a payload that the stored start schema rejects
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_definitions SET start_schema_json = $2 WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId, new JsonObject("""
                                    {"type":"object","required":["nothing-of-the-kind"]}"""))));
            Throwable invalid = failureOf(startAsync(f, node, "invalid"), IDLE);

            // And a start addresses a version that has been deprecated (the row is marked directly, because the
            // lifecycle
            // does not deprecate the current version)
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_definitions SET status = 'DEPRECATED' WHERE definition_id = $1")
                    .execute(Tuple.of(definitionId)));
            Throwable deprecated = failureOf(startAsync(f, node, "deprecated"), IDLE);

            // Then each fails with its own refusal and none with the intake refusal
            assertThat(mismatch).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(invalid).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(deprecated)
                    .isInstanceOfSatisfying(
                            WorkflowDefinitionLifecycleException.class, refusal -> assertThat(refusal.reason())
                                    .isEqualTo(WorkflowDefinitionLifecycleException.Reason.DEPRECATED));

            // And nothing was written
            assertThat(Counts.tables(f)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName(
            "with a history listener bound, a refused start and signal take no execution lock and reach the history failure path")
    void refusalsTakeNoLockAndAreReportedToTheHistoryListener() {
        try (SignalFixture f = order()) {
            // Given a node with a history listener and a count of the execution locks it takes, and a waiting instance
            List<WorkflowHistoryFailure> failures = new CopyOnWriteArrayList<>();
            WorkflowHistoryListener listener = new WorkflowHistoryListener() {
                @Override
                public void onHistoryChanged(WorkflowHistoryChange change) {}

                @Override
                public void onHistoryFailed(WorkflowHistoryFailure failure) {
                    failures.add(failure);
                }
            };
            LockCount locks = new LockCount();
            Node node = f.node("engine")
                    .intakeAdmission(admission)
                    .historyListeners(Set.of(listener))
                    .commands(locks::watch)
                    .build();
            WorkflowInstanceId waiting = f.waiting(node, "A");
            locks.reset();
            failures.clear();
            Counts before = Counts.of(f, waiting);

            // When the node refuses and a new start and a new signal arrive
            refusing.set(true);
            Throwable start = failureOf(startAsync(f, node, "B"), IDLE);
            Throwable signal =
                    failureOf(f.recordAsync(node, waiting, "paid", RecordedSignalIT.signal("paid"), "k1"), IDLE);

            // Then both fail with the intake refusal, without taking the execution lock, which a refusal under
            // pressure must not wait for
            assertThat(start).isInstanceOf(WorkflowIntakeRefusedException.class);
            assertThat(signal).isInstanceOf(WorkflowIntakeRefusedException.class);
            assertThat(locks.taken()).isZero();

            // And each refusal was reported to the listener as a failed action of its kind, with the error type only
            assertThat(failures)
                    .extracting(WorkflowHistoryFailure::action, WorkflowHistoryFailure::errorType)
                    .containsExactlyInAnyOrder(
                            tuple(WorkflowHistoryFailure.Action.START, "WorkflowIntakeRefusedException"),
                            tuple(WorkflowHistoryFailure.Action.SIGNAL, "WorkflowIntakeRefusedException"));
            assertThat(Counts.of(f, waiting)).isEqualTo(before);

            // And a retried signal that committed earlier still succeeds without the lock, while a node that admits
            // takes the lock as before
            refusing.set(false);
            f.record(node, waiting, "paid", RecordedSignalIT.signal("paid"), "k1");
            assertThat(locks.taken()).as("an admitting node takes the lock").isPositive();
            locks.reset();
            refusing.set(true);
            f.record(node, waiting, "paid", RecordedSignalIT.signal("paid"), "k1");
            assertThat(locks.taken())
                    .as("a replay on a refusing node takes none")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a cancel and a retry are recorded on a node that refuses new intake")
    void cancelAndRetryAreRecorded() {
        try (SignalFixture f = order()) {
            // Given two waiting instances, one of them failed, and a node that refuses new intake
            Node node = f.node("engine").intakeAdmission(admission).build();
            WorkflowInstanceId cancelled = f.waiting(node, "to-cancel");
            WorkflowInstanceId failed = f.waiting(node, "to-retry");
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_instances SET status = 'FAILED' WHERE id = $1")
                    .execute(Tuple.of(failed.value())));
            refusing.set(true);

            // When a cancel and a retry are recorded
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.cancel(cancelled, "operator", tx))));
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.retry(failed, tx))));

            // Then the instance is cancelled and the retry is a recorded command
            assertThat(f.snapshot(cancelled).getString("status")).isEqualTo("CANCELLED");
            assertThat(f.fixture.commandsOf(failed))
                    .extracting(ExecutorFixture.CommandRow::kind)
                    .contains("RETRY");
        }
    }

    @Test
    @DisplayName("a task completion is recorded on a node that refuses new intake")
    void taskCompletionIsRecorded() {
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new DueDateTask(), List.of("note"))) {
            // Given an instance waiting at a task, and a node that refuses new intake
            Node node = f.node("engine").intakeAdmission(admission).build();
            WorkflowInstanceId id = f.waiting(node, "task");
            UUID taskId = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getUUID("task_id");
            refusing.set(true);

            // When the completion is recorded
            var command = RecordedCompletionIT.command(
                    new RecordedCompletionIT.OpenTask(id, taskId), "approve", new JsonObject(), "complete-1", null);
            await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.taskCompleted(command, tx))));

            // Then a completion command is recorded for the instance
            assertThat(f.fixture.commandsOf(id))
                    .extracting(ExecutorFixture.CommandRow::kind)
                    .contains("COMPLETION");
        }
    }

    // --- Helpers ---

    private static Future<WorkflowInstanceId> startAsync(SignalFixture f, Node node, String ref) {
        return f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start(ref), tx)));
    }

    private static long startDedupRows(SignalFixture f, String ref) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT count(*) FROM workflow_dedup WHERE kind = 'start' AND key = $1")
                        .execute(Tuple.of(ref)))
                .iterator()
                .next()
                .getLong(0);
    }

    /** Counts the execution locks taken through the command repository the engine and the history publisher use. */
    private static final class LockCount {
        private final AtomicInteger locks = new AtomicInteger();

        @SuppressWarnings("unchecked")
        WorkflowCommandRepository<SqlClient> watch(WorkflowCommandRepository<SqlClient> delegate) {
            return (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("lockInstance")) {
                            locks.incrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        int taken() {
            return locks.get();
        }

        void reset() {
            locks.set(0);
        }
    }

    /** What a refusal and a replay must leave unchanged. */
    private record Counts(long instances, long commands, long history, long dedup, long openSignals, long queues) {

        /** The counts of every table, with no instance whose open signals are counted. */
        static Counts tables(SignalFixture f) {
            return new Counts(
                    count(f, "workflow_instances"),
                    count(f, "workflow_commands"),
                    count(f, "workflow_history"),
                    count(f, "workflow_dedup"),
                    0,
                    count(f, "workflow_command_queues"));
        }

        static Counts of(SignalFixture f, WorkflowInstanceId signalled) {
            return new Counts(
                    count(f, "workflow_instances"),
                    count(f, "workflow_commands"),
                    count(f, "workflow_history"),
                    count(f, "workflow_dedup"),
                    f.openSignals(signalled),
                    count(f, "workflow_command_queues"));
        }

        private static long count(SignalFixture f, String table) {
            return await(f.fixture
                            .observer()
                            .query("SELECT count(*) FROM " + table)
                            .execute())
                    .iterator()
                    .next()
                    .getLong(0);
        }
    }
}
