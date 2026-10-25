// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireExecutor;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.Deployment;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.MixedCodeFixtures.Shape;
import dev.vertique.workflow.postgresql.registry.failing.v1.FinanceDecision;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Verifies what a node does with work it cannot run because its deployed code lacks the version in question: a start
 * that resolves to the version, and a signal, branch signal, task completion or query on an instance pinned to it, fail
 * to the caller with a typed error and change nothing; a timer fired there is queued again as a fresh job, without
 * spending an attempt, so that a node with the code fires it; and no node fails an instance because it cannot run
 * its pin.
 *
 * <p>Two nodes share one database. Node B is deployed with version 1 of a definition only. Node A is started after it
 * with versions 1 and 2, so version 2 is published, becomes current, and reaches B only as a committed record.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class MixedCodeNodesIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** The contract of the code definitions of these tests; it declares the signals they wait for. */
    @WorkflowContract("mixed-code-c")
    public interface MixedContract extends OrderDocumentSignals {}

    /** The contract of a version that a node registers after the version that its instances are pinned to is gone. */
    @WorkflowContract("mixed-code-other")
    public interface OtherContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_mixed_code_nodes_it")
            .withMigration("classpath:db/migration/workflow");

    /** How many attempts a timer-fire job is given before the job infrastructure gives up on it. */
    private static final int MAX_ATTEMPTS = 5;

    private static final String MIXED_CONTRACT_NAME =
            MixedContract.class.getAnnotation(WorkflowContract.class).value();
    private static final String OTHER_CONTRACT_NAME =
            OtherContract.class.getAnnotation(WorkflowContract.class).value();

    @Test
    @DisplayName("a start on a node without the current version's code fails typed and creates no instance")
    void startOnNodeWithoutCurrentVersionCodeFailsTypedAndCreatesNoInstance(Vertx vertx) {
        String id = "mixed-code-start";
        DefinitionKey current = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given node B with version 1 only and node A with versions 1 and 2, B having applied the activation
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());

            // When a client on B starts the definition with an idempotency key
            Throwable refused = ActivationFixtures.failureOf(
                    () -> b.app().workflowOperations().start(start(id, "k1")));

            // Then the start fails typed, naming the scope, the id and the version and nothing else
            assertThat(refused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(current);
                assertThat(failure.scopeKey()).isEqualTo(DEFAULT);
                assertThat(failure.getMessage()).contains(DEFAULT, id, "2").doesNotContain("OrderDocState");
            });

            // And neither an instance nor a start deduplication row exists for the key
            assertThat(database.instanceCount(id)).isZero();
            assertThat(database.dedupCount(id)).isZero();

            // And the same start on A creates an instance pinned to version 2
            WorkflowInstanceId started = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, started, 0);
            assertThat(versionOf(database, started)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a signal through a node without the pinned code fails typed and leaves the instance unchanged")
    void signalThroughNodeWithoutPinnedCodeFailsTypedAndLeavesInstanceUnchanged(Vertx vertx) {
        String id = "mixed-code-signal";
        DefinitionKey pinned = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A and waiting for a signal, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            assertThat(versionOf(database, instance)).isEqualTo(2);
            Rows before = Rows.of(database, instance);
            assertThat(before.instance()).contains("WAITING");

            // When B receives the signal
            Throwable refused = ActivationFixtures.failureOf(() -> b.app()
                    .workflowOperations()
                    .signal(instance, "inventory.reserved", new InventoryReservedDoc("r1"), "d1"));

            // Then it fails typed, naming the pinned version
            assertThat(refused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(pinned);
            });

            // And the instance, its history and its deduplication rows are as they were, with no row for the key
            assertThat(Rows.of(database, instance)).isEqualTo(before);
            assertThat(dedupRowsWithKey(database, "d1")).isZero();

            // And the same signal through A, with the same key, advances the instance
            await(a.app()
                    .workflowOperations()
                    .signal(instance, "inventory.reserved", new InventoryReservedDoc("r1"), "d1"));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("task completion through a node without the pinned code fails typed and a query still reads")
    void taskCompletionThroughNodeWithoutPinnedCodeFailsTypedAndQueryReads(Vertx vertx) {
        String id = "mixed-code-task";
        DefinitionKey pinned = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A waiting for a human task, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_TASK);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            UUID task = openTask(database, instance);
            Rows before = Rows.of(database, instance);

            // When B completes the task with a valid decision, and then queries the instance
            TaskCompletionCommand completion =
                    new TaskCompletionCommand(task, "approve", null, "c1", new WorkflowActor.User("reviewer"), null);
            Throwable completionRefused = ActivationFixtures.failureOf(() ->
                    b.app().pool().withTransaction(tx -> b.app().taskCallbacks().taskCompleted(completion, tx)));
            WorkflowView readOnB = await(b.app().workflowOperations().query(instance));

            // Then the completion fails typed, naming the pinned version, and the query reads the stored instance
            assertThat(completionRefused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(pinned);
            });
            assertThat(readOnB.instance().id()).isEqualTo(instance);

            // And the task stays open and the instance is unchanged
            assertThat(Rows.of(database, instance)).isEqualTo(before);
            assertThat(before.tasks()).contains("OPEN");

            // And A's query and completion succeed
            WorkflowView view = await(a.app().workflowOperations().query(instance));
            assertThat(view.instance().status()).isEqualTo(WorkflowStatus.WAITING);
            TaskMutationResult completed = await(
                    a.app().pool().withTransaction(tx -> a.app().taskCallbacks().taskCompleted(completion, tx)));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(completed).isEqualTo(TaskMutationResult.APPLIED);
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a timer on a node without the pinned code is re-queued past the attempt limit and fires elsewhere")
    void timerOnNodeWithoutPinnedCodeIsRequeuedPastMaxAttemptsAndFiresOnCapableNode(Vertx vertx) {
        String id = "mixed-code-timer";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db);
                ExecutorLog log = new ExecutorLog()) {
            own(database, id);

            // Given a version 2 instance on A waiting for a standalone timer, and one fire executor per node
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_TIMER);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            TimerRow scheduled = onlyTimer(database, instance);
            assertThat(scheduled.status()).isEqualTo("SCHEDULED");
            SteppedClock clock = new SteppedClock(scheduled.fireAt());
            RecordingFireJob bJob = new RecordingFireJob(clock);
            WorkflowTimerFireExecutor bExecutor = executor(b, bJob, clock);
            WorkflowTimerFireExecutor aExecutor = executor(a, new RecordingFireJob(clock), clock);

            // When B's executor runs the timer once more often than the attempt limit, with the clock advancing by
            // each delay it was given
            List<Integer> warningsAfterRun = new ArrayList<>();
            for (int run = 1; run <= MAX_ATTEMPTS + 1; run++) {
                UUID executionBefore = onlyTimer(database, instance).executionId();
                assertThat(fireOn(bExecutor, scheduled.timerId(), instance))
                        .as("failure of run %s of the executor on B", run)
                        .isNull();
                assertThat(onlyTimer(database, instance).executionId()).isNotEqualTo(executionBefore);
                warningsAfterRun.add(log.warnings().size());
                clock.advance(bJob.lastDelay());
            }

            // Then the job was queued again every time, at a delay that starts at 5 seconds, never shrinks and never
            // exceeds 5 minutes
            assertThat(bJob.delays()).hasSize(MAX_ATTEMPTS + 1);
            assertThat(bJob.delays().getFirst()).isEqualTo(Duration.ofSeconds(5));
            assertThat(bJob.delays()).isSorted();
            assertThat(bJob.delays()).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(Duration.ofMinutes(5)));

            // And the row is still scheduled, pointing at the last job, and the instance is waiting and not failed
            TimerRow requeued = onlyTimer(database, instance);
            assertThat(requeued.status()).isEqualTo("SCHEDULED");
            assertThat(requeued.executionId()).isEqualTo(bJob.lastExecutionId());
            assertThat(database.instanceStatus(instance.value())).isEqualTo("WAITING");
            assertThat(historyCount(database, instance, "TIMER_FAILED")).isZero();

            // And one warning was logged for the first re-queue and none for the others within the same minute
            assertThat(warningsAfterRun.subList(0, 5)).containsOnly(1);
            assertThat(log.warnings()).hasSize(2);

            // When A's executor runs the timer
            Throwable fired = fireOn(aExecutor, scheduled.timerId(), instance);
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));

            // Then the timer is fired and the instance moved past the timer step
            assertThat(fired).as("failure of the executor on A").isNull();
            assertThat(onlyTimer(database, instance).status()).isEqualTo("FIRED");
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName(
            "a task due date firing on a node without the pinned code changes nothing and expires on a capable node")
    void taskDueDateOnNodeWithoutPinnedCodeChangesNothingAndExpiresOnCapableNode(Vertx vertx) {
        String id = "mixed-code-due";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A waiting for a task with a due date, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_TASK_WITH_DUE_DATE);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            TimerRow due = timerWithPurpose(database, instance, "TASK_DUE");
            assertThat(due.status()).isEqualTo("SCHEDULED");
            Rows before = Rows.of(database, instance);
            assertThat(before.tasks()).contains("OPEN");
            SteppedClock clock = new SteppedClock(due.fireAt());
            RecordingFireJob bJob = new RecordingFireJob(clock);

            // When B's executor runs the due-date timer
            Throwable firedOnB = fireOn(executor(b, bJob, clock), due.timerId(), instance);

            // Then the timer is queued again and stays scheduled, pointing at the new job
            assertThat(firedOnB).as("failure of the executor on B").isNull();
            assertThat(bJob.delays()).hasSize(1);
            TimerRow requeued = timerWithPurpose(database, instance, "TASK_DUE");
            assertThat(requeued.status()).isEqualTo("SCHEDULED");
            assertThat(requeued.executionId()).isEqualTo(bJob.lastExecutionId());

            // And the task is still open, not expired, and the instance, its history and its rows are as they were
            assertThat(Rows.of(database, instance).tasks()).contains("OPEN").doesNotContain("EXPIRED");
            assertThat(Rows.of(database, instance)).isEqualTo(before);
            assertThat(database.instanceStatus(instance.value())).isEqualTo("WAITING");

            // When A's executor runs the timer
            Throwable firedOnA = fireOn(executor(a, new RecordingFireJob(clock), clock), due.timerId(), instance);
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));

            // Then the task expired and the workflow moved on
            assertThat(firedOnA).as("failure of the executor on A").isNull();
            assertThat(timerWithPurpose(database, instance, "TASK_DUE").status())
                    .isEqualTo("FIRED");
            assertThat(Rows.of(database, instance).tasks()).contains("EXPIRED");
            assertThat(database.instanceStatus(instance.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName(
            "a branch task due date firing on a node without the pinned code changes nothing and expires elsewhere")
    void branchTaskDueDateOnNodeWithoutPinnedCodeChangesNothingAndExpiresOnCapableNode(Vertx vertx) {
        String id = "mixed-code-branch-due";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A whose first branch waits for a task with a due date, and B with version 1
            // only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_IN_BRANCHES_FOR_TASK_WITH_DUE_DATE);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(onDuplicatedContext(
                    vertx, () -> a.app().workflowOperations().start(start(id, "k1"))));
            awaitInitialExecution(database, instance, 2);
            TimerRow due = timerWithPurpose(database, instance, "TASK_DUE");
            Rows before = Rows.of(database, instance);
            assertThat(before.tasks()).contains("OPEN");
            SteppedClock clock = new SteppedClock(due.fireAt());
            RecordingFireJob bJob = new RecordingFireJob(clock);

            // When B's executor runs the due-date timer
            Throwable firedOnB = failureOf(onDuplicatedContext(vertx, () -> executor(b, bJob, clock)
                    .execute(new TimerFirePayload(due.timerId(), instance, null), mock(JobContext.class))));

            // Then the timer is queued again and stays scheduled, and the task, the branches and the instance are
            // as they were
            assertThat(firedOnB).as("failure of the executor on B").isNull();
            assertThat(bJob.delays()).hasSize(1);
            assertThat(timerWithPurpose(database, instance, "TASK_DUE").status())
                    .isEqualTo("SCHEDULED");
            assertThat(Rows.of(database, instance)).isEqualTo(before);

            // When A's executor runs the timer
            Throwable firedOnA =
                    failureOf(onDuplicatedContext(vertx, () -> executor(a, new RecordingFireJob(clock), clock)
                            .execute(new TimerFirePayload(due.timerId(), instance, null), mock(JobContext.class))));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));

            // Then the task expired and its branch moved on
            assertThat(firedOnA).as("failure of the executor on A").isNull();
            assertThat(timerWithPurpose(database, instance, "TASK_DUE").status())
                    .isEqualTo("FIRED");
            assertThat(Rows.of(database, instance).tasks()).contains("EXPIRED");
            assertThat(Rows.of(database, instance).branches()).isNotEqualTo(before.branches());
        }
    }

    @Test
    @DisplayName("a task reminder firing on a node without the pinned code changes nothing and fires on a capable node")
    void taskReminderOnNodeWithoutPinnedCodeChangesNothingAndFiresOnCapableNode(Vertx vertx) {
        String id = "mixed-code-reminder";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A waiting for a task with reminders after one and two hours, and B with
            // version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_TASK_WITH_REMINDERS);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            List<TimerRow> reminders = reminderTimers(database, instance);
            assertThat(reminders).hasSize(2);
            TimerRow first = reminders.getFirst();
            Rows before = Rows.of(database, instance);
            SteppedClock clock = new SteppedClock(reminders.getLast().fireAt());
            RecordingFireJob bJob = new RecordingFireJob(clock);

            // When B's executor runs the first reminder more than once
            Throwable firstRun = fireOn(executor(b, bJob, clock), first.timerId(), instance);
            Throwable secondRun = fireOn(executor(b, bJob, clock), first.timerId(), instance);

            // Then the timer is queued again each time and stays scheduled
            assertThat(firstRun).as("first failure of the executor on B").isNull();
            assertThat(secondRun).as("second failure of the executor on B").isNull();
            assertThat(bJob.delays()).hasSize(2);
            assertThat(reminderTimers(database, instance))
                    .extracting(TimerRow::status)
                    .containsOnly("SCHEDULED");

            // And the reminder counter, the history, the timers and the other rows are as they were
            assertThat(Rows.of(database, instance)).isEqualTo(before);
            assertThat(before.tasks()).endsWith(":OPEN:0");

            // When A's executor runs the first reminder
            Throwable firedOnA = fireOn(executor(a, new RecordingFireJob(clock), clock), first.timerId(), instance);
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));

            // Then it fired as reminder 1 of 2 and the second reminder is still scheduled
            assertThat(firedOnA).as("failure of the executor on A").isNull();
            assertThat(Rows.of(database, instance).tasks()).endsWith(":OPEN:1");
            assertThat(reminderIndexes(database, instance, "TASK_REMINDER_FIRED"))
                    .containsExactly("1");
            assertThat(reminderTimers(database, instance))
                    .extracting(TimerRow::status)
                    .containsExactly("FIRED", "SCHEDULED");
        }
    }

    @Test
    @DisplayName("a branch task reminder firing on a node without the pinned code changes nothing and fires elsewhere")
    void branchTaskReminderOnNodeWithoutPinnedCodeChangesNothingAndFiresOnCapableNode(Vertx vertx) {
        String id = "mixed-code-branch-reminder";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A whose first branch waits for a task with an hourly reminder, and B with
            // version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_IN_BRANCHES_FOR_TASK_WITH_REMINDERS);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(onDuplicatedContext(
                    vertx, () -> a.app().workflowOperations().start(start(id, "k1"))));
            awaitInitialExecution(database, instance, 2);
            TimerRow reminder = timerWithPurpose(database, instance, "TASK_REMINDER");
            Rows before = Rows.of(database, instance);
            assertThat(before.tasks()).endsWith(":OPEN:0");
            SteppedClock clock = new SteppedClock(reminder.fireAt());
            RecordingFireJob bJob = new RecordingFireJob(clock);

            // When B's executor runs the reminder more than once
            Throwable firstRun = failureOf(onDuplicatedContext(vertx, () -> executor(b, bJob, clock)
                    .execute(new TimerFirePayload(reminder.timerId(), instance, null), mock(JobContext.class))));
            Throwable secondRun = failureOf(onDuplicatedContext(vertx, () -> executor(b, bJob, clock)
                    .execute(new TimerFirePayload(reminder.timerId(), instance, null), mock(JobContext.class))));

            // Then the timer is queued again each time and stays scheduled
            assertThat(firstRun).as("first failure of the executor on B").isNull();
            assertThat(secondRun).as("second failure of the executor on B").isNull();
            assertThat(bJob.delays()).hasSize(2);
            assertThat(timerWithPurpose(database, instance, "TASK_REMINDER").status())
                    .isEqualTo("SCHEDULED");

            // And the reminder counter, the history, the timers, the branches and the task are as they were
            assertThat(Rows.of(database, instance)).isEqualTo(before);

            // When A's executor runs the reminder
            Throwable firedOnA = failureOf(onDuplicatedContext(vertx, () -> executor(
                            a, new RecordingFireJob(clock), clock)
                    .execute(new TimerFirePayload(reminder.timerId(), instance, null), mock(JobContext.class))));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));

            // Then it fired as reminder 1, and the next one is scheduled an hour after the first
            assertThat(firedOnA).as("failure of the executor on A").isNull();
            assertThat(Rows.of(database, instance).tasks()).endsWith(":OPEN:1");
            assertThat(reminderIndexes(database, instance, "BRANCH_TASK_REMINDER_FIRED"))
                    .containsExactly("1");
            List<TimerRow> reminders = reminderTimers(database, instance);
            assertThat(reminders).extracting(TimerRow::status).containsExactly("FIRED", "SCHEDULED");
            assertThat(reminders.getLast().fireAt()).isEqualTo(reminder.fireAt().plus(Duration.ofHours(1)));
        }
    }

    @Test
    @DisplayName("a start replay on a node without the pinned code compares the contract stored for the pin")
    void startReplayOnNodeWithoutPinnedCodeComparesTheContract(Vertx vertx) {
        String id = "mixed-code-replay";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);
            Rows before = Rows.of(database, instance);
            StartCommand otherContract = new StartCommand(
                    id, OTHER_CONTRACT_NAME, new PlaceOrderDoc("order-k1"), "k1", null, null, null, DEFAULT, null);

            // When B replays the start through the contract of the instance and through another contract
            WorkflowInstanceId sameContract = await(b.app().workflowOperations().start(start(id, "k1")));
            Throwable otherContractRefused = ActivationFixtures.failureOf(
                    () -> b.app().workflowOperations().start(otherContract));

            // Then the same contract returns the existing instance on B
            assertThat(sameContract).isEqualTo(instance);

            // and: another contract is an idempotency conflict of kind start, not the existing instance id
            assertThat(otherContractRefused).isInstanceOf(WorkflowIdempotencyConflictException.class);

            // and: once B holds no version of the id at all, the stored contract of the pin still answers the replay
            b.app().workflowRegistry().unregister(new DefinitionKey(DEFAULT, id, 1));
            assertThat(await(b.app().workflowOperations().start(start(id, "k1"))))
                    .isEqualTo(instance);
            assertThat(ActivationFixtures.failureOf(
                            () -> b.app().workflowOperations().start(otherContract)))
                    .isInstanceOf(WorkflowIdempotencyConflictException.class);

            // and: a later version registered under another contract does not replace the stored one
            b.app().workflowRegistry().register(MixedCodeFixtures.code(id, 3, Shape.COMPLETES, OtherContract.class));
            assertThat(await(b.app().workflowOperations().start(start(id, "k1"))))
                    .isEqualTo(instance);
            Throwable storedContractConflict = ActivationFixtures.failureOf(() -> b.app()
                    .workflowOperations()
                    .start(new StartCommand(
                            id,
                            OTHER_CONTRACT_NAME,
                            new PlaceOrderDoc("order-k1"),
                            "k1",
                            null,
                            null,
                            null,
                            DEFAULT,
                            null)));
            assertThat(storedContractConflict)
                    .isInstanceOf(WorkflowIdempotencyConflictException.class)
                    .hasMessageContaining(MIXED_CONTRACT_NAME);

            // and: nothing changed
            assertThat(Rows.of(database, instance)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("a start replay on a node without the pinned code takes no connection besides the start transaction")
    void startReplayOnNodeWithoutPinnedCodeTakesNoSecondConnection(Vertx vertx) {
        String id = "mixed-code-replay-pool";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance started on A, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_FOR_SIGNAL);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(a.app().workflowOperations().start(start(id, "k1")));
            awaitInitialExecution(database, instance, 0);

            // and: every connection of B's pool but one is held
            List<SqlConnection> held = new ArrayList<>();
            try {
                for (int i = 0; i < 9; i++) held.add(await(b.app().pool().getConnection()));

                // When B replays the start in a transaction on the one connection left, which must suffice
                WorkflowInstanceId replayed = await(b.app()
                        .pool()
                        .withTransaction(tx -> b.app().transactionalOperations().start(start(id, "k1"), tx))
                        .timeout(5, TimeUnit.SECONDS));

                // Then the replay returned the existing instance without waiting for another connection
                assertThat(replayed).isEqualTo(instance);
            } finally {
                held.forEach(connection -> connection.close());
            }
        }
    }

    @Test
    @DisplayName("a branch signal through a node without the pinned code fails typed and leaves the branch unchanged")
    void branchSignalThroughNodeWithoutPinnedCodeFailsTypedAndLeavesBranchUnchanged(Vertx vertx) {
        String id = "mixed-code-branch";
        DefinitionKey pinned = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a version 2 instance on A whose fork has a branch waiting for a signal, and B with version 1 only
            Boot b = nodeWithV1Only(database, id);
            Boot a = nodeWithV1AndV2(database, id, Shape.WAITS_IN_BRANCHES);
            await(b.app().definitionPropagationPoller().pollNow());
            WorkflowInstanceId instance = await(onDuplicatedContext(
                    vertx, () -> a.app().workflowOperations().start(start(id, "k1"))));
            awaitInitialExecution(database, instance, 2);
            Rows before = Rows.of(database, instance);
            assertThat(before.branches()).contains(MixedCodeFixtures.FIRST_BRANCH);

            // When B receives the branch signal through the branch-aware overload
            InventoryReservedDoc payload = new InventoryReservedDoc("r3");
            Throwable refused = ActivationFixtures.failureOf(
                    () -> onDuplicatedContext(vertx, () -> branchSignal(b, instance, payload, "d3")));

            // Then it fails typed, naming the pinned version
            assertThat(refused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(pinned);
            });

            // And the branch tokens, the instance and the history are unchanged, and no deduplication row was kept
            assertThat(Rows.of(database, instance)).isEqualTo(before);
            assertThat(dedupRowsWithKey(database, "d3")).isZero();

            // And the same call through A advances the branch
            await(onDuplicatedContext(vertx, () -> branchSignal(a, instance, payload, "d3")));
            await(a.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
            assertThat(Rows.of(database, instance).branches()).isNotEqualTo(before.branches());
        }
    }

    @Test
    @DisplayName("an instance of a failing row without a plan is refused, not failed")
    void instanceOfFailingRowWithoutAPlanIsRefusedNotFailed(Vertx vertx) {
        String id = FailingScopeRowIT.PURCHASE;
        DefinitionKey key = new DefinitionKey(FailingScopeRowIT.ACME, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db);
                ExecutorLog log = new ExecutorLog()) {
            FailingScopeRowIT.own(database, id);

            // Given the first deployment, where acme's document is published and activated, one acme instance waits
            // for a signal and another waits for a scheduled timer
            Boot first = FailingScopeRowIT.boot(database, vertx, Deployment.firstWith(id, true));
            await(first.app()
                    .definitionService()
                    .load(
                            FailingScopeRowIT.ACME,
                            timerDocument(id),
                            DocumentFormat.YAML,
                            source("acme-timer", Instant.parse("2026-10-22T12:00:00Z"))));
            await(first.app().definitionService().publish(key));
            await(first.app().definitionService().activate(FailingScopeRowIT.ACME, id, FailingScopeRowIT.ACME, 1));
            WorkflowInstanceId waitingForSignal = startInAcme(first, "signal-order");
            WorkflowInstanceId waitingForTimer = startInAcme(first, "timer-order");
            await(first.app()
                    .workflowOperations()
                    .signal(waitingForTimer, "inventory.reserved", new InventoryReservedDoc("r4"), "d-first"));
            await(first.app().executionProbe().awaitIdle(waitingForTimer, IDLE_TIMEOUT));
            TimerRow timer = onlyTimer(database, waitingForTimer);
            assertThat(timer.status()).isEqualTo("SCHEDULED");
            first.close();

            // And a restart whose catalog no longer lists a class named by the row's binding, so the row is failing
            // and has no registered plan
            Boot second = FailingScopeRowIT.boot(database, vertx, Deployment.sameStateWithoutDecision(id));
            assertThat(FailingScopeRowIT.listed(second, key).failure().rule()).isEqualTo(Rule.NAME_BINDING_UNRESOLVED);
            assertThat(second.app().workflowRegistry().find(key)).isEmpty();
            Rows before = Rows.of(database, waitingForSignal);
            Rows timerInstanceBefore = Rows.of(database, waitingForTimer);
            SteppedClock clock = new SteppedClock(timer.fireAt());
            RecordingFireJob job = new RecordingFireJob(clock);

            // When the restarted node receives a signal for the first instance and runs the timer of the second
            Throwable refused = ActivationFixtures.failureOf(() -> second.app()
                    .workflowOperations()
                    .signal(waitingForSignal, "inventory.reserved", new InventoryReservedDoc("r4"), "d4"));
            Throwable fired = fireOn(executor(second, job, clock), timer.timerId(), waitingForTimer);

            // Then the signal fails typed, naming the row, and writes nothing
            assertThat(refused).isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                assertThat(failure.definitionKey()).isEqualTo(key);
            });
            assertThat(Rows.of(database, waitingForSignal)).isEqualTo(before);
            assertThat(dedupRowsWithKey(database, "d4")).isZero();

            // And the timer is queued again with a warning, its row still scheduled and pointing at the new job
            assertThat(fired).as("failure of the executor").isNull();
            assertThat(job.delays()).hasSize(1);
            TimerRow requeued = onlyTimer(database, waitingForTimer);
            assertThat(requeued.status()).isEqualTo("SCHEDULED");
            assertThat(requeued.executionId()).isEqualTo(job.lastExecutionId());
            assertThat(log.warnings()).hasSize(1);

            // And neither instance was failed
            assertThat(Rows.of(database, waitingForTimer)).isEqualTo(timerInstanceBefore);
            assertThat(database.instanceStatus(waitingForSignal.value())).isEqualTo("WAITING");
            assertThat(database.instanceStatus(waitingForTimer.value())).isEqualTo("WAITING");
        }
    }

    // --- Nodes ---

    /**
     * Node B: deployed with version 1 of the definition only. Its command executor polls so rarely that it never looks
     * at the commands of node A: an executor without the pinned code defers a command it peeks, and these tests are
     * about what B's other operations do, with A running the instance.
     */
    private static Boot nodeWithV1Only(DefinitionScenario database, String id) {
        JsonObject config = new JsonObject()
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("pollIntervalMs", 3_600_000L)));
        return database.node(
                none(),
                unobserved(),
                List.of(MixedCodeFixtures.code(id, 1, Shape.COMPLETES, MixedContract.class)),
                config);
    }

    /** Node A: deployed with version 1 and a version 2 that stops where the shape says. */
    private static Boot nodeWithV1AndV2(DefinitionScenario database, String id, Shape version2) {
        List<WorkflowDefinition<?, ?>> code = List.of(
                MixedCodeFixtures.code(id, 1, Shape.COMPLETES, MixedContract.class),
                MixedCodeFixtures.code(id, 2, version2, MixedContract.class));
        return database.node(none(), unobserved(), code);
    }

    private static WorkflowTimerFireExecutor executor(Boot node, WorkflowTimerFireJob job, Clock clock) {
        return new WorkflowTimerFireExecutor(
                node.app().pool(),
                node.app().timerStore(),
                () -> node.app().timerCallbacks(),
                () -> node.app().taskCallbacks(),
                () -> job,
                clock);
    }

    /** Delivers the signal to the first branch of the fork, in one transaction on the node. */
    private static Future<Void> branchSignal(
            Boot node, WorkflowInstanceId instance, InventoryReservedDoc payload, String dedupKey) {
        return node.app().pool().withTransaction(tx -> node.app()
                .transactionalOperations()
                .signal(
                        instance,
                        "inventory.reserved",
                        payload,
                        dedupKey,
                        MixedCodeFixtures.FORK_STEP,
                        MixedCodeFixtures.FIRST_BRANCH,
                        tx));
    }

    /** Runs the action on a duplicated Vert.x context, which the engine requires to drive the branches of a fork. */
    private static <T> Future<T> onDuplicatedContext(Vertx vertx, Supplier<Future<T>> action) {
        Promise<T> promise = Promise.promise();
        ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(ignored -> action.get()
                .onComplete(promise));
        return promise.future();
    }

    /** Runs the timer on the executor and returns how the run failed, or {@code null} when it succeeded. */
    private static Throwable fireOn(WorkflowTimerFireExecutor executor, UUID timerId, WorkflowInstanceId instance) {
        return failureOf(executor.execute(new TimerFirePayload(timerId, instance, null), mock(JobContext.class)));
    }

    // --- Commands and documents ---

    private static StartCommand start(String id, String idempotencyKey) {
        return new StartCommand(
                id,
                MIXED_CONTRACT_NAME,
                new PlaceOrderDoc("order-" + idempotencyKey),
                idempotencyKey,
                null,
                null,
                null,
                DEFAULT,
                null);
    }

    private static WorkflowInstanceId startInAcme(Boot node, String orderId) {
        WorkflowInstanceId instance = await(node.app()
                .workflowOperations()
                .start(FailingScopeRowIT.command(
                        FailingScopeRowIT.ACME,
                        FailingScopeRowIT.PURCHASE,
                        orderId,
                        "start-" + UUID.randomUUID(),
                        null,
                        null)));
        await(node.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
        return instance;
    }

    /** A document that waits for a signal, then for a timer, then for a finance decision named by its binary name. */
    private static byte[] timerDocument(String id) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-approval
                steps:
                  - id: wait-approval
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: defer
                  - id: defer
                    type: timer
                    fireAt: PT1H
                    next: finance-review
                  - id: finance-review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                  - id: done
                    type: complete
                """.formatted(id, FailingScopeRowIT.ContractC.class.getName(), FinanceDecision.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }

    // --- Rows ---

    /** Everything a refused call must leave as it was, read straight from the tables. */
    private record Rows(String instance, long history, long dedup, String branches, String tasks, String timers) {

        static Rows of(DefinitionScenario database, WorkflowInstanceId id) {
            Row instance = one(database, """
                    SELECT definition_version, version, status, current_step_id, wait_type, wait_key, state_json::text AS state
                    FROM workflow_instances WHERE id = $1
                    """, id.value());
            String instanceText = String.join(
                    "|",
                    String.valueOf(instance.getLong("definition_version")),
                    String.valueOf(instance.getLong("version")),
                    instance.getString("status"),
                    instance.getString("current_step_id"),
                    String.valueOf(instance.getString("wait_type")),
                    String.valueOf(instance.getString("wait_key")),
                    instance.getString("state"));
            return new Rows(
                    instanceText,
                    count(
                            database,
                            "SELECT count(*) AS total FROM workflow_history WHERE workflow_id = $1",
                            id.value()),
                    count(database, "SELECT count(*) AS total FROM workflow_dedup WHERE workflow_id = $1", id.value()),
                    joined(database, """
                            SELECT branch_id || ':' || current_step_id || ':' || status AS item
                            FROM workflow_branch_tokens WHERE workflow_id = $1 ORDER BY branch_id
                            """, id.value()),
                    joined(database, """
                            SELECT task_id::text || ':' || status || ':' || reminders_fired_count AS item
                            FROM workflow_tasks WHERE workflow_id = $1 ORDER BY task_id
                            """, id.value()),
                    joined(database, """
                            SELECT purpose || ':' || status || ':' || fire_at AS item
                            FROM workflow_timers WHERE workflow_id = $1 ORDER BY fire_at, timer_id
                            """, id.value()));
        }
    }

    /** One timer row, as the tables hold it. */
    private record TimerRow(UUID timerId, String status, UUID executionId, Instant fireAt) {}

    private static TimerRow onlyTimer(DefinitionScenario database, WorkflowInstanceId instance) {
        Row row = one(database, """
                SELECT timer_id, status, delayed_job_execution_id, fire_at
                FROM workflow_timers WHERE workflow_id = $1
                """, instance.value());
        return new TimerRow(
                row.getUUID("timer_id"),
                row.getString("status"),
                row.getUUID("delayed_job_execution_id"),
                row.getOffsetDateTime("fire_at").toInstant());
    }

    private static TimerRow timerWithPurpose(DefinitionScenario database, WorkflowInstanceId instance, String purpose) {
        Row row = one(database, """
                SELECT timer_id, status, delayed_job_execution_id, fire_at
                FROM workflow_timers WHERE workflow_id = $1 AND purpose = $2
                """, instance.value(), purpose);
        return new TimerRow(
                row.getUUID("timer_id"),
                row.getString("status"),
                row.getUUID("delayed_job_execution_id"),
                row.getOffsetDateTime("fire_at").toInstant());
    }

    /** The reminder timers of the instance, earliest first. */
    private static List<TimerRow> reminderTimers(DefinitionScenario database, WorkflowInstanceId instance) {
        List<TimerRow> timers = new ArrayList<>();
        for (Row row : await(database.admin().preparedQuery("""
                        SELECT timer_id, status, delayed_job_execution_id, fire_at
                        FROM workflow_timers WHERE workflow_id = $1 AND purpose = 'TASK_REMINDER'
                        ORDER BY fire_at, timer_id
                        """).execute(Tuple.of(instance.value())))) {
            timers.add(new TimerRow(
                    row.getUUID("timer_id"),
                    row.getString("status"),
                    row.getUUID("delayed_job_execution_id"),
                    row.getOffsetDateTime("fire_at").toInstant()));
        }
        return timers;
    }

    /** The reminder indexes recorded by history entries of the type, in the order they were recorded. */
    private static List<String> reminderIndexes(
            DefinitionScenario database, WorkflowInstanceId instance, String entryType) {
        List<String> indexes = new ArrayList<>();
        for (Row row : await(database.admin().preparedQuery("""
                        SELECT payload_json ->> 'reminderIndex' AS item
                        FROM workflow_history WHERE workflow_id = $1 AND entry_type = $2 ORDER BY sequence
                        """).execute(Tuple.of(instance.value(), entryType)))) {
            indexes.add(row.getString("item"));
        }
        return indexes;
    }

    private static UUID openTask(DefinitionScenario database, WorkflowInstanceId instance) {
        return one(
                        database,
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'",
                        instance.value())
                .getUUID("task_id");
    }

    private static long versionOf(DefinitionScenario database, WorkflowInstanceId instance) {
        return one(database, "SELECT definition_version FROM workflow_instances WHERE id = $1", instance.value())
                .getLong("definition_version");
    }

    private static long dedupRowsWithKey(DefinitionScenario database, String key) {
        return count(database, "SELECT count(*) AS total FROM workflow_dedup WHERE key = $1", key);
    }

    private static long historyCount(DefinitionScenario database, WorkflowInstanceId instance, String entryType) {
        return count(
                database,
                "SELECT count(*) AS total FROM workflow_history WHERE workflow_id = $1 AND entry_type = $2",
                instance.value(),
                entryType);
    }

    private static Row one(DefinitionScenario database, String sql, Object... parameters) {
        return await(database.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))
                .iterator()
                .next();
    }

    private static long count(DefinitionScenario database, String sql, Object... parameters) {
        return one(database, sql, parameters).getLong("total");
    }

    private static String joined(DefinitionScenario database, String sql, Object... parameters) {
        List<String> items = new ArrayList<>();
        for (Row row : await(database.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))) {
            items.add(row.getString("item"));
        }
        return String.join(",", items);
    }

    private record StartSnapshot(
            String commandState,
            java.time.OffsetDateTime availableAt,
            Integer deferralCount,
            Integer attempts,
            String lastError,
            String instanceStatus,
            long branchCount) {}

    /** Waits for the START command and its setup state; awaitIdle only means no command is currently runnable. */
    private static void awaitInitialExecution(
            DefinitionScenario database, WorkflowInstanceId instance, long expectedBranchCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        StartSnapshot snapshot = null;
        while (true) {
            var rows = await(database.admin().preparedQuery("""
                            SELECT c.state AS command_state, c.available_at, c.deferral_count, c.attempts, c.last_error,
                                   i.status AS instance_status,
                                   count(b.branch_id) AS branch_count
                            FROM workflow_instances i
                            LEFT JOIN workflow_commands c ON c.workflow_id = i.id AND c.kind = 'START'
                            LEFT JOIN workflow_branch_tokens b ON b.workflow_id = i.id
                            WHERE i.id = $1
                            GROUP BY c.state, c.available_at, c.deferral_count, c.attempts, c.last_error, i.status
                            """).execute(Tuple.of(instance.value())));
            if (rows.iterator().hasNext()) {
                Row row = rows.iterator().next();
                snapshot = new StartSnapshot(
                        row.getString("command_state"),
                        row.getOffsetDateTime("available_at"),
                        row.getInteger("deferral_count"),
                        row.getInteger("attempts"),
                        row.getString("last_error"),
                        row.getString("instance_status"),
                        row.getLong("branch_count"));
                if ("COMPLETE".equals(snapshot.commandState())
                        && !"PENDING".equals(snapshot.instanceStatus())
                        && snapshot.branchCount() >= expectedBranchCount) {
                    return;
                }
                if ("FAILED".equals(snapshot.instanceStatus())
                        || List.of("DEAD", "REJECTED", "FAILED", "CANCELLED").contains(snapshot.commandState())) {
                    throw new AssertionError("START did not complete successfully for " + instance + ": " + snapshot);
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("START setup did not reach its expected state for " + instance + ": "
                        + (snapshot == null ? "instance or START command row is missing" : snapshot));
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting START setup for " + instance, e);
            }
        }
    }

    // --- Fixtures ---

    private static void own(DefinitionScenario database, String id) {
        ActivationFixtures.clearActivations(database.admin(), id);
        database.ownRows(id);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), id));
    }

    private static BootObservation unobserved() {
        return new BootObservation("mixed-code-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    /** A clock that only moves when the test says so. */
    private static final class SteppedClock extends Clock {

        private Instant now;

        private SteppedClock(Instant start) {
            this.now = start.truncatedTo(ChronoUnit.MICROS);
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A timer-fire job that records what is queued, and the delay from the clock, and hands out fresh ids. */
    private static final class RecordingFireJob implements WorkflowTimerFireJob {

        private final Clock clock;
        private final List<Duration> delays = new ArrayList<>();
        private final List<UUID> executionIds = new ArrayList<>();

        private RecordingFireJob(Clock clock) {
            this.clock = clock;
        }

        List<Duration> delays() {
            return List.copyOf(delays);
        }

        Duration lastDelay() {
            return delays.getLast();
        }

        UUID lastExecutionId() {
            return executionIds.getLast();
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload, DelayedJobOptions options, SqlClient tx) {
            delays.add(Duration.between(clock.instant(), options.runAt()));
            UUID executionId = UUID.randomUUID();
            executionIds.add(executionId);
            return Future.succeededFuture(executionId);
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload) {
            throw new UnsupportedOperationException("only the transactional enqueue with options is expected");
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload, Instant runAt) {
            throw new UnsupportedOperationException("only the transactional enqueue with options is expected");
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload, Duration delay) {
            throw new UnsupportedOperationException("only the transactional enqueue with options is expected");
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload, SqlClient tx) {
            throw new UnsupportedOperationException("only the transactional enqueue with options is expected");
        }

        @Override
        public Future<UUID> enqueue(TimerFirePayload payload, DelayedJobOptions options) {
            throw new UnsupportedOperationException("only the transactional enqueue with options is expected");
        }
    }

    /** Collects what the timer-fire executor logs at warning level or above while it is open. */
    private static final class ExecutorLog implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkflowTimerFireExecutor.class.getName());
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = logger.getLevel();

        private ExecutorLog() {
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        List<String> warnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
