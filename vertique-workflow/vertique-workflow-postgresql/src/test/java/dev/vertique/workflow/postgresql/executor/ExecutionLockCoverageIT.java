// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.LONG_LOCK_TIMEOUT_MS;
import static dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.Assembly;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.Held;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.LockStart;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.TickContract;
import dev.vertique.workflow.postgresql.executor.ExecutionLockTestSupport.TwoSessions;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that every entry point that changes an instance holds the instance's execution lock when it returns, so that
 * a path that is added or changed without taking the lock fails here by name.
 *
 * <p>Each entry point runs on an instance prepared for it, in a transaction that stays open after the call. The proof
 * looks at the lock table for the backend of that transaction and asks a second session for the lock of the same
 * instance in try mode.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExecutionLockCoverageIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_execution_lock_coverage_test")
            .withMigration("classpath:db/migration/workflow");

    private static Vertx vertx;
    private static Pool pool;
    private static Assembly assembly;

    @BeforeAll
    static void setUp(Vertx vertx) {
        ExecutionLockCoverageIT.vertx = vertx;
        pool = ExecutionLockTestSupport.pool(vertx, db.toPoolConfig(), 20);
        assembly = Assembly.open(vertx, pool, LONG_LOCK_TIMEOUT_MS, true);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * One call of an entry point, ready to run in a caller's transaction.
     *
     * @param instance the instance the call changes, or {@code null} when the call creates it
     * @param call     runs the entry point and completes with the id of the instance it changed
     */
    private record Call(WorkflowInstanceId instance, Function<SqlClient, Future<WorkflowInstanceId>> call) {}

    /**
     * An entry point and the preparation of the instance it needs.
     *
     * @param name    the name of the entry point
     * @param prepare creates the instance in the state the entry point works on and returns the call
     */
    private record EntryPoint(String name, Function<Assembly, Call> prepare) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<EntryPoint> entryPoints() {
        return List.of(
                new EntryPoint("start", ExecutionLockCoverageIT::start),
                new EntryPoint("signal", ExecutionLockCoverageIT::signal),
                new EntryPoint("signal to a branch", ExecutionLockCoverageIT::signalToBranch),
                new EntryPoint("signal to a branch with metadata", ExecutionLockCoverageIT::signalToBranchWithMetadata),
                new EntryPoint("cancel", ExecutionLockCoverageIT::cancel),
                new EntryPoint("retry", ExecutionLockCoverageIT::retry),
                new EntryPoint("migrate", ExecutionLockCoverageIT::migrate),
                new EntryPoint("migrate to a scope", ExecutionLockCoverageIT::migrateToScope),
                new EntryPoint("task completed", ExecutionLockCoverageIT::taskCompleted),
                new EntryPoint("task due fired", ExecutionLockCoverageIT::taskDueFired),
                new EntryPoint("task reassigned", ExecutionLockCoverageIT::taskReassigned),
                new EntryPoint("task reminder fired", ExecutionLockCoverageIT::taskReminderFired),
                new EntryPoint("timer fired", ExecutionLockCoverageIT::timerFired),
                new EntryPoint("timer firing failed", ExecutionLockCoverageIT::timerFiringFailed),
                new EntryPoint("recovered branch driven", ExecutionLockCoverageIT::driveRecoveredBranch),
                new EntryPoint(
                        "recovered branch evaluated", ExecutionLockCoverageIT::evaluateRecoveredBranchIfTerminal));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entryPoints")
    void everyInstanceChangingEntryPointTakesTheExecutionLock(EntryPoint entryPoint) {
        // Given an instance in the state the entry point works on
        Call prepared = entryPoint.prepare().apply(assembly);
        AtomicReference<String> outcome = new AtomicReference<>("did not run");
        try (TwoSessions sessions = new TwoSessions(assembly.runner(), pool, vertx)) {

            // When the entry point runs in a caller transaction that is held open after the call
            Held<WorkflowInstanceId> held = sessions.holdOpen(tx -> prepared.call()
                    .apply(tx)
                    .map(id -> {
                        outcome.set("succeeded");
                        return id;
                    })
                    .recover(failure -> {
                        outcome.set("failed with " + failure);
                        return Future.succeededFuture(prepared.instance());
                    }));
            WorkflowInstanceId changed = held.ready();
            assertNotNull(changed, "the entry point did not name the instance it changed; it " + outcome.get());

            // Then this backend holds a two-integer advisory lock
            assertFalse(
                    sessions.twoIntegerLocksHeldBy(held.pid()).isEmpty(),
                    entryPoint + " holds no execution lock after the call; the call " + outcome.get());

            // And that lock is the one of the instance the call changed
            boolean otherSessionGotIt = await(
                    pool.withTransaction(tx -> assembly.commands().tryLockInstance(changed, tx)),
                    Duration.ofSeconds(5));
            assertFalse(
                    otherSessionGotIt,
                    entryPoint + " does not hold the execution lock of instance " + changed.value() + "; the call "
                            + outcome.get());
        }
    }

    // --- Entry points ---

    private static String key() {
        return "coverage-" + UUID.randomUUID();
    }

    private static TransactionalWorkflowOperations<SqlClient> operations(Assembly a) {
        return a.component().transactionalOperations();
    }

    private static TransactionalTaskCallbacks<SqlClient> tasks(Assembly a) {
        return a.component().taskCallbacks();
    }

    private static TransactionalTimerCallbacks<SqlClient> timers(Assembly a) {
        return a.component().timerCallbacks();
    }

    private static Call start(Assembly a) {
        String key = key();
        StartCommand command = new StartCommand(
                ExecutionLockTestSupport.TICK,
                TickContract.class.getName(),
                new LockStart(key),
                key,
                null,
                null,
                null,
                null,
                null);
        return new Call(null, tx -> operations(a).start(command, tx));
    }

    private static Call signal(Assembly a) {
        WorkflowInstanceId id = a.startTick();
        return new Call(
                id, tx -> operations(a).signal(id, "tick", "payload", key(), tx).map(v -> id));
    }

    private static Call signalToBranch(Assembly a) {
        WorkflowInstanceId id = a.startFork();
        return new Call(id, tx -> operations(a)
                .signal(id, "signal-a", "payload", key(), "fork", "a", tx)
                .map(v -> id));
    }

    private static Call signalToBranchWithMetadata(Assembly a) {
        WorkflowInstanceId id = a.startFork();
        return new Call(id, tx -> operations(a)
                .signal(id, "signal-b", "payload", key(), "fork", "b", DurableMetadata.empty(), tx)
                .map(v -> id));
    }

    private static Call cancel(Assembly a) {
        WorkflowInstanceId id = a.startTick();
        return new Call(id, tx -> operations(a).cancel(id, "coverage", tx).map(v -> id));
    }

    private static Call retry(Assembly a) {
        WorkflowInstanceId id = a.startTick();
        a.failInstance(id);
        return new Call(id, tx -> operations(a).retry(id, tx).map(v -> id));
    }

    private static Call migrate(Assembly a) {
        WorkflowInstanceId id = a.startMigrating();
        return new Call(id, tx -> operations(a).migrate(id, 2L, tx).map(v -> id));
    }

    private static Call migrateToScope(Assembly a) {
        WorkflowInstanceId id = a.startMigrating();
        return new Call(
                id,
                tx -> operations(a).migrate(id, WorkflowScopes.DEFAULT, 2L, tx).map(v -> id));
    }

    private static Call taskCompleted(Assembly a) {
        WorkflowInstanceId id = a.startTask();
        UUID taskId = a.waitKeyOf(id);
        TaskCompletionCommand command =
                new TaskCompletionCommand(taskId, "approve", "ok", key(), new WorkflowActor.User("alice"), null);
        return new Call(id, tx -> tasks(a).taskCompleted(command, tx).map(result -> id));
    }

    private static Call taskDueFired(Assembly a) {
        WorkflowInstanceId id = a.startTask();
        UUID taskId = a.waitKeyOf(id);
        return new Call(id, tx -> tasks(a).taskDueFired(id, taskId, tx).map(result -> id));
    }

    private static Call taskReassigned(Assembly a) {
        WorkflowInstanceId id = a.startTask();
        UUID taskId = a.waitKeyOf(id);
        TaskReassignmentCommand command = new TaskReassignmentCommand(
                taskId, new TaskAssignment.Role("legal"), new WorkflowActor.User("bob"), key(), "coverage");
        return new Call(id, tx -> tasks(a).taskReassigned(command, tx).map(result -> id));
    }

    private static Call taskReminderFired(Assembly a) {
        WorkflowInstanceId id = a.startTask();
        UUID taskId = a.waitKeyOf(id);
        Row reminder = a.timerOf(id, "TASK_REMINDER");
        UUID timerId = reminder.getUUID("timer_id");
        java.time.Instant fireAt = reminder.getOffsetDateTime("fire_at").toInstant();
        return new Call(id, tx -> tasks(a).taskReminderFired(id, taskId, timerId, fireAt, tx)
                .map(v -> id));
    }

    private static Call timerFired(Assembly a) {
        WorkflowInstanceId id = a.startTimer();
        UUID timerId = a.waitKeyOf(id);
        return new Call(id, tx -> timers(a).timerFired(id, timerId, tx).map(result -> id));
    }

    private static Call timerFiringFailed(Assembly a) {
        WorkflowInstanceId id = a.startTimer();
        UUID timerId = a.waitKeyOf(id);
        return new Call(id, tx -> timers(a)
                .timerFiringFailed(id, timerId, "ERROR", "coverage", tx)
                .map(result -> id));
    }

    private static Call driveRecoveredBranch(Assembly a) {
        WorkflowInstanceId id = a.startFork();
        a.setBranchStatus(id, "a", "RUNNING");
        var token = a.branchToken(id, "a");
        WorkflowInstance instance =
                await(a.component().instances().findById(id)).orElseThrow();
        var runtime = a.runtime(ExecutionLockTestSupport.FORK, 1L);
        return new Call(id, tx -> a.component()
                .recoveryBridge()
                .driveRecoveredBranch(token, instance.stateJson(), null, runtime, tx, null)
                .map(driven -> id));
    }

    private static Call evaluateRecoveredBranchIfTerminal(Assembly a) {
        WorkflowInstanceId id = a.startFork();
        a.setBranchStatus(id, "a", "FAILED");
        var token = a.branchToken(id, "a");
        WorkflowInstance instance =
                await(a.component().instances().findById(id)).orElseThrow();
        var runtime = a.runtime(ExecutionLockTestSupport.FORK, 1L);
        return new Call(id, tx -> a.component()
                .recoveryBridge()
                .evaluateRecoveredBranchIfTerminal(instance, token, runtime, tx)
                .map(v -> id));
    }
}
