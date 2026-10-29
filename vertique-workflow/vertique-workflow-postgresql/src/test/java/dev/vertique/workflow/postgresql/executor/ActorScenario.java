// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.DueDateTask;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimeoutWait;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.tasks.TaskAssignment;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A node over a migrated database with one fixture workflow, whose recording calls run under a chosen security identity
 * and whose commands and history entries are read back with the actor columns they carry.
 *
 * <p>This type opens the package-private executor fixtures to the attribution proofs, which live beside the engine
 * proofs. Everything it exposes is a public type of the module under test or a plain value.
 */
public final class ActorScenario implements AutoCloseable {

    /** The reason the scenario cancels instances with. */
    public static final String WHY = "operator-cancelled";

    /**
     * One command or one history entry with the actor columns it carries.
     *
     * @param label       the kind of the command or the type of the entry
     * @param commandId   the command the row is, or the command the entry belongs to; null when it names none
     * @param actorKind   {@code actor_kind}
     * @param actorId     {@code actor_id}
     * @param actorReason {@code actor_reason}
     * @param onBehalfOf  {@code on_behalf_of}
     * @param attribution {@code attribution_status}
     */
    public record Attributed(
            String label,
            @Nullable Long commandId,
            @Nullable String actorKind,
            @Nullable String actorId,
            @Nullable String actorReason,
            @Nullable String onBehalfOf,
            @Nullable String attribution) {

        /** Whether the row carries exactly this kind, id and attribution status. */
        public boolean is(String kind, String id, String status) {
            return kind.equals(actorKind) && java.util.Objects.equals(id, actorId) && status.equals(attribution);
        }
    }

    private final SignalFixture f;
    private final Node node;

    private final CommandFaults faults = new CommandFaults();

    private ActorScenario(DbPoolConfig db, RecordedSignalIT.Definition definition, List<String> signalNames) {
        this(db, definition, signalNames, false);
    }

    private ActorScenario(
            DbPoolConfig db, RecordedSignalIT.Definition definition, List<String> signalNames, boolean faulty) {
        this.f = new SignalFixture(db, definition, signalNames);
        try {
            ExecutorFixture.NodeSpec spec = f.node("engine");
            if (faulty) {
                spec = spec.leaseSeconds(5).maxAttempts(1).commands(faults.decorator());
            }
            this.node = spec.build();
        } catch (RuntimeException | AssertionError e) {
            f.close();
            throw e;
        }
    }

    /**
     * A scenario over a workflow whose first wait has a timeout, so that its first segment records a timer command.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static ActorScenario timeoutWait(DbPoolConfig db) {
        return new ActorScenario(db, new TimeoutWait(), List.of("approve", "note", "finish"));
    }

    /**
     * A scenario over a workflow that waits at a task with a due date.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static ActorScenario dueDateTask(DbPoolConfig db) {
        return new ActorScenario(db, new DueDateTask(), List.of("note"));
    }

    /**
     * A scenario over a workflow that waits at a task with a due date, on a node whose segments can be made to crash
     * until the commands recorded for an instance are dead-lettered.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static ActorScenario deadLetteringTask(DbPoolConfig db) {
        return new ActorScenario(db, new DueDateTask(), List.of("note"), true);
    }

    /**
     * A scenario over a workflow whose first wait has a timeout and accepts the signal {@code approve}, on a node whose
     * segments can be made to crash until the commands recorded for an instance are dead-lettered.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static ActorScenario deadLetteringWait(DbPoolConfig db) {
        return new ActorScenario(db, new TimeoutWait(), List.of("approve", "note", "finish"), true);
    }

    // --- Recording, under an identity ---

    /**
     * Records a start in a transaction of its own, under the identity, and commits it.
     *
     * @param caller  the identity the call runs under, or null for none
     * @param ref     the label of the instance
     * @param payload extra fields of the start payload, such as a forged actor
     * @return the instance
     */
    public WorkflowInstanceId start(@Nullable SecurityIdentity caller, String ref, Map<String, Object> payload) {
        StartCommand base = f.start(ref);
        Map<String, Object> body = new java.util.LinkedHashMap<>(payload);
        body.put("ref", ref);
        StartCommand command = new StartCommand(
                base.definitionId(),
                base.contractName(),
                body,
                base.idempotencyKey(),
                base.businessKey(),
                base.subjectRef(),
                base.requestedDefinitionVersion(),
                base.startScope(),
                base.requestedTargetScope());
        return await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.start(command, tx))));
    }

    /**
     * Records a signal in a transaction of its own, under the identity, and commits it. No executor runs.
     *
     * @param caller the identity the call runs under, or null for none
     * @param id     the instance
     * @param name   the signal name
     * @param key    the dedup key
     */
    public void signal(@Nullable SecurityIdentity caller, WorkflowInstanceId id, String name, String key) {
        await(as(
                caller,
                () -> node.pool.withTransaction(
                        tx -> node.engine.signal(id, name, RecordedSignalIT.signal(key), key, tx))));
    }

    /**
     * Reassigns the instance's open task to another user in a transaction of its own, under the identity.
     *
     * @param caller     the identity the call runs under, or null for none
     * @param id         the instance
     * @param newUser    the user the task is assigned to
     * @param reassigner the user the command names as the reassigner
     * @param key        the idempotency key
     */
    public void reassignTask(
            @Nullable SecurityIdentity caller, WorkflowInstanceId id, String newUser, String reassigner, String key) {
        UUID taskId = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getUUID("task_id");
        TaskReassignmentCommand command = new TaskReassignmentCommand(
                taskId, new TaskAssignment.User(newUser), new WorkflowActor.User(reassigner), key, "handover");
        await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.taskReassigned(command, tx))));
    }

    /**
     * Cancels the instance in a transaction of its own, under the identity, and commits it.
     *
     * @param caller the identity the call runs under, or null for none
     * @param id     the instance
     */
    public void cancel(@Nullable SecurityIdentity caller, WorkflowInstanceId id) {
        await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.cancel(id, WHY, tx))));
    }

    /**
     * Completes the instance's open task in a transaction of its own, under the identity, and commits it.
     *
     * @param caller the identity the call runs under, or null for none
     * @param id     the instance
     * @param key    the idempotency key
     */
    public void completeTask(@Nullable SecurityIdentity caller, WorkflowInstanceId id, String key) {
        UUID taskId = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getUUID("task_id");
        var command = RecordedCompletionIT.command(
                new RecordedCompletionIT.OpenTask(id, taskId),
                "approve",
                new io.vertx.core.json.JsonObject(),
                key,
                null);
        await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.taskCompleted(command, tx))));
    }

    /**
     * Records a retry of the instance in a transaction of its own, under the identity, and commits it.
     *
     * @param caller the identity the call runs under, or null for none
     * @param id     the instance
     */
    public void retry(@Nullable SecurityIdentity caller, WorkflowInstanceId id) {
        await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.retry(id, tx))));
    }

    // --- Running ---

    /**
     * Starts the executor, waits until the instance is idle and stops it.
     *
     * @param id the instance
     */
    public void runToIdle(WorkflowInstanceId id) {
        f.idle(node, id);
    }

    /**
     * Runs an executor that lacks the scenario's workflow until the signal is deferred as the head of its instance.
     *
     * @param id  the instance
     * @param key the dedup key of the signal
     */
    public void deferSignal(WorkflowInstanceId id, String key) {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new RecordedSignalIT.OrderSignalFixture("deferring-other-order"));
        Node lacking = f.node("lacking").withRegistry(registry).build();
        lacking.startExecutor();
        try {
            ExecutorFixture.awaitCondition("the signal to be deferred", ExecutorFixture.IDLE, () -> f.signal(id, key)
                    .state()
                    .equals("DEFERRED"));
        } finally {
            lacking.stopExecutor();
        }
    }

    /**
     * Makes every segment of the instance crash and runs the executor until the signal and the completion recorded for
     * it are dead-lettered. Only a scenario made by {@link #deadLetteringTask} can do this.
     *
     * @param id            the instance
     * @param signalKey     the dedup key of the signal
     * @param completionKey the idempotency key of the completion
     */
    public void deadLetter(WorkflowInstanceId id, String signalKey, String completionKey) {
        deadLetter(id, signalKey, completionKey, "the test abandoned the segment");
    }

    /**
     * Makes every segment of the instance crash with a failure that carries the message, and runs the executor until
     * the signal and the completion recorded for it are dead-lettered. Only a scenario made by
     * {@link #deadLetteringTask} can do this.
     *
     * @param id            the instance
     * @param signalKey     the dedup key of the signal
     * @param completionKey the idempotency key of the completion
     * @param failureText   the message of the failure that abandons each segment
     */
    public void deadLetter(WorkflowInstanceId id, String signalKey, String completionKey, String failureText) {
        faults.crashEverySegmentOf(id, failureText);
        node.startExecutor();
        try {
            ExecutorFixture.awaitCondition(
                    "the signal and the completion to be dead-lettered",
                    java.time.Duration.ofSeconds(45),
                    () -> f.signal(id, signalKey).state().equals("DEAD")
                            && f.fixture.commandsOf(id).stream()
                                    .filter(row -> row.kind().equals("COMPLETION"))
                                    .allMatch(row -> row.state().equals("DEAD")));
        } finally {
            faults.crashSegmentsOf(id, 0);
            node.stopExecutor();
        }
    }

    // --- Observation ---

    /**
     * A pool of the scenario's database for reading it.
     *
     * @return the pool
     */
    public io.vertx.sqlclient.Pool observer() {
        return f.fixture.observer();
    }

    /**
     * The state of every command of the instance, by command id.
     *
     * @param id the instance
     * @return the states
     */
    public Map<Long, String> commandStates(WorkflowInstanceId id) {
        Map<Long, String> states = new java.util.LinkedHashMap<>();
        f.fixture.commandsOf(id).forEach(row -> states.put(row.id(), row.state()));
        return states;
    }

    /**
     * The commands of the instance in queue order with their actor columns.
     *
     * @param id the instance
     * @return the rows
     */
    public List<Attributed> commands(WorkflowInstanceId id) {
        List<Attributed> rows = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT kind, id, actor_kind, actor_id, actor_reason, on_behalf_of, attribution_status"
                        + " FROM workflow_commands WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            rows.add(attributed(row.getString("kind"), row.getLong("id"), row));
        }
        return rows;
    }

    /**
     * The history entries of the instance in sequence order with their actor columns. The command of an entry is its
     * {@code command_id}, or the {@code commandId} of its payload when the column is null.
     *
     * @param id the instance
     * @return the rows
     */
    public List<Attributed> history(WorkflowInstanceId id) {
        List<Attributed> rows = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT entry_type, COALESCE(command_id, (payload_json->>'commandId')::bigint)"
                        + " AS command, actor_kind, actor_id, actor_reason, on_behalf_of, attribution_status"
                        + " FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            rows.add(attributed(row.getString("entry_type"), row.getLong("command"), row));
        }
        return rows;
    }

    /**
     * The events the node's executor observers received, in order.
     *
     * @return the events
     */
    public List<WorkflowExecutorEvent> events() {
        return List.copyOf(f.observer.events);
    }

    /**
     * Waits until the observers received at least the number of events matching the predicate.
     *
     * @param count     the least number of events
     * @param predicate the events to count
     */
    public void awaitEvents(int count, java.util.function.Predicate<WorkflowExecutorEvent> predicate) {
        ExecutorFixture.awaitCondition(
                "events to be delivered",
                ExecutorFixture.IDLE,
                () -> events().stream().filter(predicate).count() >= count);
    }

    @Override
    public void close() {
        f.close();
    }

    // --- Plumbing ---

    private static Attributed attributed(String label, @Nullable Long commandId, Row row) {
        return new Attributed(
                label,
                commandId,
                row.getString("actor_kind"),
                row.getString("actor_id"),
                row.getString("actor_reason"),
                row.getString("on_behalf_of"),
                row.getString("attribution_status"));
    }

    /** Runs the work on a duplicated context of its own with the identity bound as the security context. */
    private <T> Future<T> as(@Nullable SecurityIdentity caller, Supplier<Future<T>> work) {
        return f.caller(() -> {
            if (caller == null) {
                return work.get();
            }
            ContextHolder.Scope scope =
                    f.fixture.holder().bind(SecurityContext.class, SecurityContexts.unauthenticated(caller));
            return work.get().onComplete(ignored -> scope.close());
        });
    }
}
