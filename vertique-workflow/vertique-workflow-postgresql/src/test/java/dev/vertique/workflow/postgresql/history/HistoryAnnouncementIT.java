// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import static dev.vertique.workflow.postgresql.history.AuditRun.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.history.CommandKind;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.history.WorkflowHistoryCommit;
import dev.vertique.workflow.history.WorkflowHistoryEvent;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Approve;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Contract;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Go;
import dev.vertique.workflow.postgresql.history.HistoryGraph.Start;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies, over a real database and a running executor, that a history listener is told every history entry once, in
 * order, after the transaction that wrote it committed, and that an action which fails is reported once, whichever
 * transaction owner drove it. A listener that throws is bound next to the recording one throughout.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class HistoryAnnouncementIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_history_announcement_it");

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    private static void migrate(String location, String historyTable, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(location)
                .table(historyTable);
        if (baseline) {
            flyway.baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    // --- Tests ---

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("every committed entry is announced once and in order, whoever owned the transaction")
    void everyCommittedEntryIsAnnouncedOnceInOrder(Vertx vertx) {
        RecordedHistory recorded = new RecordedHistory();
        try (AuditRun run = AuditRun.startAnnouncing(vertx, db, recorded)) {
            // Given an instance that a caller started, whose task a caller reassigned and approved, and which a runner
            // owned transaction signalled to its end, and an instance that was cancelled at its task
            WorkflowInstanceId ended = startAs(run, "u-0", "ended");
            UUID task = taskOf(run, ended);
            run.asUser("u-3", () -> run.pool().withTransaction(tx -> run.graph()
                    .taskCallbacks()
                    .taskReassigned(
                            new TaskReassignmentCommand(
                                    task,
                                    new TaskAssignment.Role("other-auditors"),
                                    new WorkflowActor.User("u-3"),
                                    "reassign-key",
                                    "out of office"),
                            tx)));
            run.completeTaskAs(AuditRun.TASK_USER, ended);
            run.asUser("u-4", () -> run.graph().operations().signal(ended, HistoryGraph.SIGNAL, new Go("v"), "go-key"));
            await(run.graph().executionProbe().awaitIdle(ended, Duration.ofSeconds(15)));
            WorkflowInstanceId cancelled = startAs(run, "u-0", "cancelled");
            run.asUser("u-5", () -> run.graph().operations().cancel(cancelled, "not needed"));
            await(run.graph().executionProbe().awaitIdle(cancelled, Duration.ofSeconds(15)));

            // When the listener has caught up with the database
            awaitUntil("the announcements to catch up", () -> caughtUp(run, recorded, ended, cancelled));

            // Then each instance's announced sequences are exactly its stored ones, once each and in order
            for (WorkflowInstanceId id : List.of(ended, cancelled)) {
                assertThat(sequencesOf(recorded.eventsOf(id))).isEqualTo(storedSequences(run, id));
            }

            // And the commits of an instance do not overlap and follow one another
            for (WorkflowInstanceId id : List.of(ended, cancelled)) {
                long last = 0;
                for (WorkflowHistoryCommit commit : recorded.commits()) {
                    if (!commit.instanceId().equals(id)) {
                        continue;
                    }
                    List<Long> sequences = sequencesOf(commit.events());
                    assertThat(sequences).isNotEmpty().isSorted();
                    assertThat(sequences.getFirst()).isGreaterThan(last);
                    last = sequences.getLast();
                }
            }

            // And the events carry their variant, command and actor
            List<WorkflowHistoryEvent> endedEvents = recorded.eventsOf(ended);
            assertThat(endedEvents.getFirst()).isInstanceOf(WorkflowHistoryEvent.CommandChanged.class);
            assertThat(endedEvents).anyMatch(WorkflowHistoryEvent.InstanceStarted.class::isInstance);
            assertThat(endedEvents).anyMatch(WorkflowHistoryEvent.InstanceCompleted.class::isInstance);
            WorkflowHistoryEvent.TaskReassigned reassigned =
                    only(endedEvents, WorkflowHistoryEvent.TaskReassigned.class);
            assertThat(reassigned.taskId()).isEqualTo(task);
            assertThat(reassigned.to()).isEqualTo(new TaskAssignment.Role("other-auditors"));
            WorkflowHistoryEvent.TaskDecided decided = only(endedEvents, WorkflowHistoryEvent.TaskDecided.class);
            assertThat(decided.decisionName()).isEqualTo("approve");
            assertThat(decided.entryInfo().actor().id()).isEqualTo(AuditRun.TASK_USER);
            assertThat(decisionOf(endedEvents, WorkflowHistoryEvent.DecisionKind.TASK_DECIDED)
                            .entryInfo())
                    .satisfies(info -> {
                        assertThat(info.commandKind()).isEqualTo(CommandKind.COMPLETION);
                        assertThat(info.actor().id()).isEqualTo(AuditRun.TASK_USER);
                    });
            assertThat(decisionOf(endedEvents, WorkflowHistoryEvent.DecisionKind.SIGNAL_APPLIED)
                            .entryInfo()
                            .commandKind())
                    .isEqualTo(CommandKind.SIGNAL);
            only(endedEvents, WorkflowHistoryEvent.SignalReceived.class);
            only(recorded.eventsOf(cancelled), WorkflowHistoryEvent.InstanceCancelled.class);

            // And every entry names who is behind it: the user who recorded the command it belongs to (an entry of a
            // segment that names no command takes the actor of the command the segment executes), the user who
            // reassigned the task, and the user who cancelled
            for (WorkflowHistoryEvent event : endedEvents) {
                CommandKind kind = event.entryInfo().commandKind();
                if (kind == null && !(event instanceof WorkflowHistoryEvent.TaskReassigned)) {
                    assertThat(event.entryInfo().actor().id())
                            .as(event.toString())
                            .isIn("u-0", "u-1", "u-4");
                    assertThat(event.entryInfo().attribution()).isEqualTo(HistoryAttribution.VERIFIED);
                    continue;
                }
                String expected = event instanceof WorkflowHistoryEvent.TaskReassigned
                        ? "u-3"
                        : switch (kind) {
                            case START -> "u-0";
                            case COMPLETION -> "u-1";
                            case SIGNAL -> "u-4";
                            default -> throw new AssertionError("unexpected command kind " + kind + " of " + event);
                        };
                assertThat(event.entryInfo().actor().id()).as(event.toString()).isEqualTo(expected);
                assertThat(event.entryInfo().attribution()).isEqualTo(HistoryAttribution.VERIFIED);
            }
            // and the entries that name their command carry its kind, none inferred from the segment
            assertThat(endedEvents)
                    .extracting(event -> event.entryInfo().commandKind())
                    .contains(CommandKind.START, CommandKind.COMPLETION, CommandKind.SIGNAL);
            assertThat(recorded.eventsOf(cancelled))
                    .filteredOn(event -> event instanceof WorkflowHistoryEvent.InstanceCancelled
                            || event instanceof WorkflowHistoryEvent.TaskCancelled)
                    .hasSize(2)
                    .allSatisfy(
                            event -> assertThat(event.entryInfo().actor().id()).isEqualTo("u-5"));

            // And no action failed, while the throwing listener was told of every commit and did not alter one
            assertThat(recorded.failures()).isEmpty();
            assertThat(recorded.thrown()).isEqualTo(recorded.commits().size());
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a refused action is reported once with its instance, and announces nothing")
    void refusedActionsAreReportedOnce(Vertx vertx) {
        RecordedHistory recorded = new RecordedHistory();
        try (AuditRun run = AuditRun.startAnnouncing(vertx, db, recorded)) {
            // Given an instance at its task, with its announcements delivered
            WorkflowInstanceId id = run.startAtTask("refused");
            awaitUntil("the start to be announced", () -> !recorded.eventsOf(id).isEmpty());
            int announced = recorded.commits().size();

            // When a completion of a task that does not exist is driven in a caller's transaction
            TaskCompletionCommand unknown = new TaskCompletionCommand(
                    UUID.randomUUID(),
                    "approve",
                    new Approve("fine"),
                    "unknown-key",
                    new WorkflowActor.User(AuditRun.TASK_USER),
                    null);
            Throwable completionFailure = failureOf(
                    run.pool().withTransaction(tx -> run.graph().taskCallbacks().taskCompleted(unknown, tx)));

            // And a retry of an instance that has nothing to recover is driven by the runner
            Throwable retryFailure = failureOf(run.graph().operations().retry(id));
            awaitUntil("the failures to be reported", () -> recorded.failures().size() >= 2);

            // Then each is reported once, for its action, with the simple name of its own failure
            List<WorkflowHistoryFailure> failures = recorded.failures();
            assertThat(failures).hasSize(2);
            WorkflowHistoryFailure completion = failureOf(failures, WorkflowHistoryFailure.Action.TASK_COMPLETE);
            assertThat(completion.instanceId()).isNull();
            assertThat(completion.errorType())
                    .isEqualTo(completionFailure.getClass().getSimpleName());
            assertThat(completion.attemptId()).isNotNull();
            WorkflowHistoryFailure retry = failureOf(failures, WorkflowHistoryFailure.Action.RETRY);
            assertThat(retry.instanceId()).isEqualTo(id);
            assertThat(retry.errorType()).isEqualTo(retryFailure.getClass().getSimpleName());
            assertThat(failures).extracting(WorkflowHistoryFailure::attemptId).doesNotHaveDuplicates();

            // And neither announced anything
            assertThat(recorded.commits()).hasSize(announced);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a caller's transaction announces after its commit, and reports instead when it rolls back")
    void callersTransactionAnnouncesAfterItsCommit(Vertx vertx) {
        RecordedHistory recorded = new RecordedHistory();
        try (AuditRun run = AuditRun.startAnnouncing(vertx, db, recorded)) {
            // Given a start that a caller commits, which is told nothing before the commit
            int[] announcedInside = {-1};
            WorkflowInstanceId committed = await(run.pool().withTransaction(tx -> run.graph()
                    .transactionalOperations()
                    .start(startCommand("committed"), tx)
                    .map(id -> {
                        announcedInside[0] = recorded.commits().size();
                        return id;
                    })));

            // Then it is announced once the caller has committed
            awaitUntil("the start to be announced", () -> !recorded.eventsOf(committed)
                    .isEmpty());
            assertThat(announcedInside[0]).isZero();
            assertThat(recorded.eventsOf(committed).getFirst())
                    .isInstanceOfSatisfying(
                            WorkflowHistoryEvent.CommandChanged.class, received -> assertThat(received.disposition())
                                    .isEqualTo(WorkflowHistoryEvent.CommandDisposition.RECEIVED));
            assertThat(recorded.failures()).isEmpty();

            // When a caller starts another instance and then rolls its transaction back
            WorkflowInstanceId[] rolledBack = new WorkflowInstanceId[1];
            Throwable rollback = failureOf(run.pool().withTransaction(tx -> run.graph()
                    .transactionalOperations()
                    .start(startCommand("rolled-back"), tx)
                    .compose(id -> {
                        rolledBack[0] = id;
                        return Future.failedFuture(new IllegalStateException("the caller gives up"));
                    })));

            // Then the start is reported as failed, once, and none of its entries is announced or stored
            awaitUntil("the failure to be reported", () -> !recorded.failures().isEmpty());
            assertThat(rollback).isInstanceOf(IllegalStateException.class);
            assertThat(recorded.failures()).hasSize(1);
            WorkflowHistoryFailure failure = recorded.failures().getFirst();
            assertThat(failure.action()).isEqualTo(WorkflowHistoryFailure.Action.START);
            assertThat(failure.instanceId()).isEqualTo(rolledBack[0]);
            assertThat(recorded.eventsOf(rolledBack[0])).isEmpty();
            assertThat(storedSequences(run, rolledBack[0])).isEmpty();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("sequences reused after a rollback to a savepoint in a caller's transaction are announced once")
    void sequencesReusedAfterASavepointRollbackAreAnnouncedOnce(Vertx vertx) {
        RecordedHistory recorded = new RecordedHistory();
        try (AuditRun run = AuditRun.startAnnouncing(vertx, db, recorded)) {
            // Given an instance at its task, with its announcements delivered
            WorkflowInstanceId id = run.startAtTask("savepoint");
            awaitUntil("the start to be announced", () -> !recorded.eventsOf(id).isEmpty());

            // When a caller cancels the instance after a savepoint, rolls back to the savepoint, which removes the
            // entries of that cancellation, cancels it again so that the same sequences are written again, and commits
            await(run.pool().withTransaction(tx -> tx.query("SAVEPOINT before_cancel")
                    .execute()
                    .compose(ignored -> run.graph().transactionalOperations().cancel(id, "undone", tx))
                    .compose(ignored ->
                            tx.query("ROLLBACK TO SAVEPOINT before_cancel").execute())
                    .compose(ignored -> run.graph().transactionalOperations().cancel(id, "kept", tx))));
            await(run.graph().executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
            awaitUntil("the announcements to catch up", () -> caughtUp(run, recorded, id));

            // Then the cancellation is announced once, and every stored sequence exactly once, in order
            only(recorded.eventsOf(id), WorkflowHistoryEvent.InstanceCancelled.class);
            assertThat(sequencesOf(recorded.eventsOf(id))).isEqualTo(storedSequences(run, id));
            assertThat(recorded.failures()).isEmpty();
        }
    }

    // --- Helpers ---

    private static WorkflowInstanceId startAs(AuditRun run, String user, String ref) {
        WorkflowInstanceId id = run.asUser(user, () -> run.pool()
                .withTransaction(tx -> run.graph().transactionalOperations().start(startCommand(ref), tx)));
        await(run.graph().executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
        return id;
    }

    private static StartCommand startCommand(String ref) {
        return new StartCommand(
                HistoryGraph.DEFINITION_ID,
                Contract.class.getName(),
                new Start(ref),
                ref,
                null,
                null,
                null,
                null,
                null);
    }

    private static UUID taskOf(AuditRun run, WorkflowInstanceId id) {
        return await(run.pool()
                        .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getUUID("task_id");
    }

    /** The sequences the instance's committed history holds, without the state baselines that are not announced. */
    private static List<Long> storedSequences(AuditRun run, WorkflowInstanceId id) {
        List<Long> sequences = new ArrayList<>();
        for (Row row : await(run.pool()
                .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                        + " AND entry_type <> 'STATE_BASELINE' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            sequences.add(row.getLong("sequence"));
        }
        return sequences;
    }

    private static List<Long> sequencesOf(List<WorkflowHistoryEvent> events) {
        return events.stream().map(event -> event.entryInfo().sequence()).toList();
    }

    private static boolean caughtUp(AuditRun run, RecordedHistory recorded, WorkflowInstanceId... ids) {
        for (WorkflowInstanceId id : ids) {
            if (sequencesOf(recorded.eventsOf(id)).size()
                    < storedSequences(run, id).size()) {
                return false;
            }
        }
        return true;
    }

    private static WorkflowHistoryEvent.StepDecided decisionOf(
            List<WorkflowHistoryEvent> events, WorkflowHistoryEvent.DecisionKind kind) {
        List<WorkflowHistoryEvent.StepDecided> matching = events.stream()
                .filter(WorkflowHistoryEvent.StepDecided.class::isInstance)
                .map(WorkflowHistoryEvent.StepDecided.class::cast)
                .filter(decision -> decision.kind() == kind)
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static <E extends WorkflowHistoryEvent> E only(List<WorkflowHistoryEvent> events, Class<E> type) {
        List<E> matching =
                events.stream().filter(type::isInstance).map(type::cast).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static WorkflowHistoryFailure failureOf(
            List<WorkflowHistoryFailure> failures, WorkflowHistoryFailure.Action action) {
        List<WorkflowHistoryFailure> matching =
                failures.stream().filter(failure -> failure.action() == action).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static Throwable failureOf(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("the future did not complete", e);
        }
        throw new AssertionError("the action was expected to fail");
    }

    private static void awaitUntil(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("timed out waiting for " + description);
    }
}
