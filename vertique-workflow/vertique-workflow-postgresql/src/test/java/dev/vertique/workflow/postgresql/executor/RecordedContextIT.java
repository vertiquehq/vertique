// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.SecurityContext;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CorrelationCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.IdentitySnapshotCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.Decision;
import dev.vertique.workflow.postgresql.executor.RecordedCompletionIT.OpenTask;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.ExplicitCarriers;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Start;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.State;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TaskDriver;
import dev.vertique.workflow.postgresql.executor.TimerCommandIT.TimerFixture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that every command stores the durable context of its recording transaction and that the executor binds each
 * operation's own recorded context, never an identity, in a segment.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordedContextIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_context_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String IDENTITY_NAMESPACE = "identity-snapshot";

    @Test
    @DisplayName(
            "a parked signal's intents carry its own recorded correlation, not that of the command that applies it")
    void parkedSignalIntentsCarryItsOwnCorrelation() {
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new ShippingWithIntents(), List.of("approve", "shipped"))) {
            CorrelatedCalls calls = new CorrelatedCalls(f.fixture);
            Node node = f.node("engine").carryContext().build();
            // Given an instance waiting at approve, and shipped recorded under correlation X and parked
            WorkflowInstanceId id = f.waiting(node, "parked-correlation");
            await(calls.under(
                    "X",
                    () -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "shipped", signal("early"), "shipped-key", tx))));
            f.idle(node, id);
            assertThat(f.signal(id, "shipped-key").state()).isEqualTo("PARKED");

            // When approve is recorded under correlation Y and the executor runs it
            await(calls.under(
                    "Y",
                    () -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "approve", signal("approve"), "approve-key", tx))));
            f.idle(node, id);

            // Then the intent written by shipped's operation carries X and the one written by approve's path carries Y
            assertThat(f.signal(id, "approve-key").state()).isEqualTo("COMPLETE");
            assertThat(f.signal(id, "shipped-key").state()).isEqualTo("COMPLETE");
            assertThat(correlationsOf(f, id))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("approve-intent", "Y", "ship-intent", "X"));
        }
    }

    @Test
    @DisplayName("no security context is bound in a segment and no command row stores an identity snapshot")
    void segmentBindsNoSecurityContextAndRowsCarryNoIdentitySnapshot() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new ProbedOrder(probe), List.of("paid"))) {
            probe.attach(f.fixture);
            Node node = f.node("engine").identityCarriage().build();
            ExecutorFixture.IdentityFixture identity = f.fixture.identity();
            // Given an authenticated caller whose durable context holds correlation X, tenant T and an identity
            // snapshot

            // When the caller records a start and a signal, and the executor runs both
            WorkflowInstanceId id = await(f.caller(() -> identity.securedCaller(
                    () -> node.pool.withTransaction(tx -> node.engine.start(f.start("identity-rows"), tx)))));
            await(f.caller(() -> identity.securedCaller(() ->
                    node.pool.withTransaction(tx -> node.engine.signal(id, "paid", signal("paid"), "paid-key", tx)))));
            f.idle(node, id);

            // Then neither segment saw a security context, an origin or an identity snapshot, and kept its context
            assertThat(f.signal(id, "paid-key").state()).isEqualTo("COMPLETE");
            assertThat(probe.at("start")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains(ExecutorFixture.IdentityFixture.CORRELATION);
            });
            assertThat(probe.at("paid")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains(ExecutorFixture.IdentityFixture.CORRELATION);
                assertThat(seen.tenant()).contains(ExecutorFixture.IdentityFixture.TENANT);
            });
            // And both command rows hold the other namespaces but no identity snapshot
            Map<String, JsonObject> contexts = contextsOf(f, id);
            for (String kind : List.of("START", "SIGNAL")) {
                JsonObject stored = contexts.get(kind);
                assertThat(stored).as(kind + " context_json").isNotNull();
                DurableMetadata recorded = DurableMetadata.fromCarrier(stored);
                assertThat(recorded.body("correlation"))
                        .as(kind + " correlation")
                        .contains(new JsonObject().put("correlationId", ExecutorFixture.IdentityFixture.CORRELATION));
                assertThat(recorded.body("tenant"))
                        .as(kind + " tenant")
                        .contains(new JsonObject().put("tenantId", ExecutorFixture.IdentityFixture.TENANT));
                assertThat(recorded.has(IDENTITY_NAMESPACE))
                        .as(kind + " identity namespace")
                        .isFalse();
                assertThat(stored.encode())
                        .as(kind + " raw context")
                        .doesNotContain(IDENTITY_NAMESPACE, ExecutorFixture.IdentityFixture.SUBJECT);
            }
        }
    }

    @Test
    @DisplayName(
            "a branch signal without an explicit carrier binds the branch token's carrier, not the recorded context")
    void branchSignalBindsTheBranchCarrierNotTheRecordedContext() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = branchFixture(probe)) {
            probe.attach(f.fixture);
            CorrelatedCalls calls = new CorrelatedCalls(f.fixture);
            Node node = f.node("engine").carryContext().build();
            // Given a fork whose branch token carries correlation C, the branch waiting on ack
            WorkflowInstanceId id = f.waiting(node, "branch-carrier");
            seedTokenCarrier(f, id, "a", DurableMetadata.of("correlation", new JsonObject().put("correlationId", "C")));

            // When ack is recorded to the branch under correlation B without an explicit carrier
            await(calls.under(
                    "B",
                    () -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "ack", signal("ack"), "ack-key", "fork", "a", tx))));
            f.idle(node, id);

            // Then the intent of the branch's path carries C
            assertThat(f.signal(id, "ack-key").state()).isEqualTo("COMPLETE");
            assertThat(correlationsOf(f, id)).containsOnlyKeys("a-intent").containsEntry("a-intent", "C");
        }
    }

    @Test
    @DisplayName("a signal to an instance without a stored carrier binds the correlation it was recorded under")
    void signalWithoutInstanceCarrierBindsItsRecordedCorrelation() {
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(), new RecordedSignalIT.OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            CorrelatedCalls calls = new CorrelatedCalls(f.fixture);
            Node node = f.node("engine").carryContext().build();
            // Given an instance started with no durable context, waiting on paid
            WorkflowInstanceId id = f.waiting(node, "plain");
            assertThat(f.fixture.instanceCarrierOf(id)).as("instance carrier").isNull();

            // When paid is recorded under correlation X without an explicit carrier
            await(calls.under(
                    "X",
                    () -> node.pool.withTransaction(
                            tx -> node.engine.signal(id, "paid", signal("paid"), "paid-key", tx))));
            f.idle(node, id);

            // Then the intent the paid updater's path wrote carries X
            assertThat(f.signal(id, "paid-key").state()).isEqualTo("COMPLETE");
            assertThat(correlationsOf(f, id)).containsOnlyKeys("paid-intent").containsEntry("paid-intent", "X");
        }
    }

    @Test
    @DisplayName("an identity snapshot in a stored explicit carrier or instance carrier is never bound")
    void explicitCarrierIdentitySnapshotIsNeverStoredOrBound() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new ProbedOrder(probe), List.of("paid"))) {
            probe.attach(f.fixture);
            Node node = f.node("engine").identityCarriage().build();
            // Given an instance waiting on paid whose stored carrier, as a row written earlier can, also holds an
            // identity snapshot next to correlation I and tenant T
            WorkflowInstanceId id = f.waiting(node, "explicit-identity");
            DurableMetadata instanceCarrier = DurableMetadata.of(
                            "correlation", new JsonObject().put("correlationId", "I"))
                    .with("tenant", new JsonObject().put("tenantId", "T"))
                    .with(IDENTITY_NAMESPACE, new JsonObject().put("subject", "user-77"));
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_instances SET metadata = $1 WHERE id = $2")
                    .execute(Tuple.of(instanceCarrier.toCarrier(), id.value())));

            // When paid is recorded with an explicit carrier holding correlation E and an identity snapshot
            DurableMetadata explicit = ExplicitCarriers.withSnapshot("E");
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "paid", signal("paid"), "paid-key", null, null, explicit, tx))));
            JsonObject stored = f.row(
                            "SELECT explicit_carrier_json FROM workflow_commands WHERE workflow_id = $1 AND"
                                    + " kind = 'SIGNAL'",
                            id)
                    .getJsonObject("explicit_carrier_json");
            assertThat(DurableMetadata.fromCarrier(stored).has(IDENTITY_NAMESPACE))
                    .as("the recorded explicit carrier")
                    .isFalse();
            // and the stored row holds a snapshot anyway, as one written by an earlier release can
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_commands SET explicit_carrier_json = $1 WHERE workflow_id = $2"
                            + " AND kind = 'SIGNAL'")
                    .execute(Tuple.of(explicit.toCarrier(), id.value())));
            f.idle(node, id);

            // Then the callback ran with correlation E and the instance's other namespaces, with no identity bound
            assertThat(f.signal(id, "paid-key").state()).isEqualTo("COMPLETE");
            assertThat(probe.at("paid")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains("E");
                assertThat(seen.tenant()).contains("T");
            });
            assertThat(correlationsOf(f, id)).containsEntry("paid-intent", "E");
        }
    }

    @Test
    @DisplayName("a branch drive binds no identity snapshot from the branch token's carrier")
    void branchDriveStripsTheIdentitySnapshotFromTheBranchCarrier() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = branchFixture(probe)) {
            probe.attach(f.fixture);
            Node node = f.node("engine").identityCarriage().build();
            // Given a fork whose branch token carries correlation C and an identity snapshot, the branch waiting on ack
            WorkflowInstanceId id = f.waiting(node, "branch-identity");
            seedTokenCarrier(
                    f,
                    id,
                    "a",
                    DurableMetadata.of("correlation", new JsonObject().put("correlationId", "C"))
                            .with(IDENTITY_NAMESPACE, new JsonObject().put("subject", "user-77")));

            // When ack is recorded to the branch without an explicit carrier
            await(f.caller(() -> node.pool.withTransaction(
                    tx -> node.engine.signal(id, "ack", signal("ack"), "ack-key", "fork", "a", tx))));
            f.idle(node, id);

            // Then the branch's step saw correlation C and no identity, and the signal completed
            assertThat(f.signal(id, "ack-key").state()).isEqualTo("COMPLETE");
            assertThat(probe.at("ack")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains("C");
            });
            assertThat(correlationsOf(f, id)).containsOnlyKeys("a-intent").containsEntry("a-intent", "C");
        }
    }

    @Test
    @DisplayName(
            "a branch timer and a branch task completion bind no identity snapshot from the branch token's carrier")
    void branchTimerAndTaskDrivesStripTheIdentitySnapshot() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f =
                new SignalFixture(db.toPoolConfig(), new BranchWithTimerAndTask(probe), List.of(), new JsonObject())) {
            probe.attach(f.fixture);
            TimerFixture timers = new TimerFixture(f);
            TaskDriver tasks = new TaskDriver(f);
            Node node = f.node("engine").identityCarriage().build();

            // Given a fork whose two branch tokens carry correlation C and an identity snapshot, one branch waiting
            // on a timer and the other on a task
            WorkflowInstanceId id = f.waiting(node, "branch-timer-task");
            DurableMetadata carrier = DurableMetadata.of("correlation", new JsonObject().put("correlationId", "C"))
                    .with(IDENTITY_NAMESPACE, new JsonObject().put("subject", "user-77"));
            seedTokenCarrier(f, id, "a", carrier);
            seedTokenCarrier(f, id, "b", carrier);

            // When the branch timer fires
            timers.dueNow(timers.only(id, "FIRE").id());
            f.idle(node, id);

            // Then the branch's next step saw correlation C and no identity
            assertThat(probe.at("timer")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains("C");
            });

            // When the branch task is completed
            UUID taskId = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1")
                            .execute(Tuple.of(id.value())))
                    .iterator()
                    .next()
                    .getUUID("task_id");
            tasks.complete(node, new OpenTask(id, taskId), new JsonObject(), "task-key");
            f.idle(node, id);

            // Then that branch's next step saw correlation C and no identity either, and the instance ended
            assertThat(probe.at("task")).singleElement().satisfies(seen -> {
                assertNoIdentityBound(seen);
                assertThat(seen.correlation()).contains("C");
            });
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName(
            "an executor started from a context with an identity and an origin bound binds only each instance's own")
    void executorStartedFromADirtyContextBindsOnlyEachInstancesOwnContext() {
        SecurityProbe probe = new SecurityProbe();
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), new ProbedOrder(probe), List.of("paid"))) {
            probe.attach(f.fixture);
            CorrelatedCalls calls = new CorrelatedCalls(f.fixture);
            Node node = f.node("engine").identityCarriage().build();
            DirtyContext dirty = DirtyContext.authenticated(f.fixture, "D");
            try {
                // Given a context with an authenticated security context, a deferred-execution origin and correlation
                // D bound, from which the stopped executor is started
                dirty.startExecutorOf(node);

                // When two callers concurrently record one start each, under correlations X and Y
                Future<WorkflowInstanceId> first = calls.under(
                        "X", () -> node.pool.withTransaction(tx -> node.engine.start(f.start("dirty-x"), tx)));
                Future<WorkflowInstanceId> second = calls.under(
                        "Y", () -> node.pool.withTransaction(tx -> node.engine.start(f.start("dirty-y"), tx)));
                WorkflowInstanceId x = await(first);
                WorkflowInstanceId y = await(second);
                node.awaitIdle(x);
                node.awaitIdle(y);

                // Then both instances wait, no start command failed, and each saw only its own context
                assertThat(f.fixture.instanceOf(x).status()).isEqualTo("WAITING");
                assertThat(f.fixture.instanceOf(y).status()).isEqualTo("WAITING");
                assertThat(f.fixture.commandsOf(x))
                        .extracting(ExecutorFixture.CommandRow::state)
                        .containsOnly("COMPLETE");
                assertThat(f.fixture.commandsOf(y))
                        .extracting(ExecutorFixture.CommandRow::state)
                        .containsOnly("COMPLETE");
                assertThat(probe.at("start")).hasSize(2).allSatisfy(seen -> {
                    assertNoIdentityBound(seen);
                    assertThat(seen.correlation()).isNotEqualTo(Optional.of("D"));
                });
                assertThat(probe.at("start"))
                        .filteredOn(seen -> seen.ref().equals("dirty-x"))
                        .singleElement()
                        .satisfies(seen -> assertThat(seen.correlation()).contains("X"));
                assertThat(probe.at("start"))
                        .filteredOn(seen -> seen.ref().equals("dirty-y"))
                        .singleElement()
                        .satisfies(seen -> assertThat(seen.correlation()).contains("Y"));
                assertThat(correlationsOf(f, x)).containsEntry("start-intent", "X");
                assertThat(correlationsOf(f, y)).containsEntry("start-intent", "Y");
                // And each start command stored the context of its own recording transaction
                assertThat(startContextOf(f, x).body("correlation"))
                        .contains(new JsonObject().put("correlationId", "X"));
                assertThat(startContextOf(f, y).body("correlation"))
                        .contains(new JsonObject().put("correlationId", "Y"));
            } finally {
                try {
                    node.stopExecutor();
                } finally {
                    dirty.clear();
                }
            }
        }
    }

    // --- Observation ---

    /** The correlation each of the instance's intents was written under, by the step that wrote it. */
    private static Map<String, String> correlationsOf(SignalFixture f, WorkflowInstanceId id) {
        Map<String, String> correlations = new HashMap<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT step_id, correlation FROM signal_it_outbox WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())))) {
            correlations.put(row.getString("step_id"), row.getString("correlation"));
        }
        return correlations;
    }

    /** The stored context of each of the instance's commands, by kind; a command with none maps to null. */
    private static Map<String, JsonObject> contextsOf(SignalFixture f, WorkflowInstanceId id) {
        Map<String, JsonObject> contexts = new HashMap<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT kind, context_json FROM workflow_commands WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            contexts.put(row.getString("kind"), (JsonObject) row.getValue("context_json"));
        }
        return contexts;
    }

    private static DurableMetadata startContextOf(SignalFixture f, WorkflowInstanceId id) {
        JsonObject stored = contextsOf(f, id).get("START");
        assertThat(stored).as("START context_json").isNotNull();
        return DurableMetadata.fromCarrier(stored);
    }

    private static void assertNoIdentityBound(Observation seen) {
        assertThat(seen.securityContext())
                .as(seen.where() + ": security context bound")
                .isFalse();
        assertThat(seen.origin())
                .as(seen.where() + ": deferred-execution origin bound")
                .isFalse();
        assertThat(seen.identitySnapshot())
                .as(seen.where() + ": identity snapshot bound")
                .isFalse();
        assertThat(seen.namespaces())
                .as(seen.where() + ": bound durable document")
                .doesNotContain(IDENTITY_NAMESPACE);
    }

    private static void seedTokenCarrier(
            SignalFixture f, WorkflowInstanceId id, String branch, DurableMetadata carrier) {
        await(f.fixture
                .observer()
                .preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1 WHERE workflow_id = $2 AND"
                        + " branch_id = $3")
                .execute(Tuple.of(carrier.toCarrier(), id.value(), branch)));
    }

    private static SignalFixture branchFixture(SecurityProbe probe) {
        return new SignalFixture(
                db.toPoolConfig(),
                new BranchWithIntent(probe),
                List.of(),
                new JsonObject().put("fork/a", new JsonArray().add("ack")).put("fork/b", new JsonArray().add("note")));
    }

    // --- Fixtures ---

    /**
     * What a step callback saw bound while it ran.
     *
     * @param where           the label of the callback
     * @param ref             the label of the instance the callback ran for
     * @param securityContext whether a security context was bound
     * @param origin          whether a deferred-execution origin was bound
     * @param identitySnapshot whether an identity snapshot was bound
     * @param correlation     the bound correlation identifier
     * @param tenant          the bound tenant
     * @param namespaces      the namespaces of the bound durable document
     */
    record Observation(
            String where,
            String ref,
            boolean securityContext,
            boolean origin,
            boolean identitySnapshot,
            Optional<String> correlation,
            Optional<String> tenant,
            Set<String> namespaces) {}

    /** Records what the bound context looks like from inside a step callback, on the thread that runs it. */
    static final class SecurityProbe {
        private volatile ExecutorFixture fixture;
        private final List<Observation> seen = new CopyOnWriteArrayList<>();

        void attach(ExecutorFixture fixture) {
            this.fixture = fixture;
        }

        void observe(String where, String ref) {
            var holder = fixture.holder();
            seen.add(new Observation(
                    where,
                    ref,
                    holder.current(SecurityContext.class).isPresent(),
                    holder.current(DeferredExecutionOrigin.class).isPresent(),
                    holder.current(IdentitySnapshotCtx.class).isPresent(),
                    holder.current(CorrelationCtx.class).map(CorrelationCtx::correlationId),
                    holder.current(TenantCtx.class).map(TenantCtx::tenantId),
                    fixture.propagator().capture(DispatchBoundary.WORKFLOW).namespaces()));
        }

        List<Observation> at(String where) {
            return seen.stream()
                    .filter(observation -> observation.where().equals(where))
                    .toList();
        }
    }

    /** Runs a caller transaction on a context of its own with the correlation bound, as a request would. */
    static final class CorrelatedCalls {
        private final ExecutorFixture fixture;

        CorrelatedCalls(ExecutorFixture fixture) {
            this.fixture = fixture;
        }

        <T> Future<T> under(String correlation, Supplier<Future<T>> work) {
            Promise<T> result = Promise.promise();
            ContextInternal context = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
            context.runOnContext(ignored -> {
                ContextHolder.Scope scope =
                        fixture.holder().bind(CorrelationCtx.class, new CorrelationCtx(correlation));
                try {
                    work.get().onComplete(outcome -> {
                        scope.close();
                        result.handle(outcome);
                    });
                } catch (RuntimeException e) {
                    scope.close();
                    result.tryFail(e);
                }
            });
            return result.future();
        }
    }

    /**
     * A duplicated Vert.x context that has an authenticated security context, a deferred-execution origin and a
     * correlation bound, from which an executor is started.
     */
    static final class DirtyContext {
        private final ExecutorFixture fixture;
        private final String correlation;
        private final ContextInternal context;
        private final List<ContextHolder.Scope> scopes = new ArrayList<>();

        private DirtyContext(ExecutorFixture fixture, String correlation) {
            this.fixture = fixture;
            this.correlation = correlation;
            this.context = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
        }

        static DirtyContext authenticated(ExecutorFixture fixture, String correlation) {
            return new DirtyContext(fixture, correlation);
        }

        void startExecutorOf(Node node) {
            Promise<Void> started = Promise.promise();
            context.runOnContext(ignored -> {
                var holder = fixture.holder();
                scopes.add(holder.bind(SecurityContext.class, ExecutorFixture.securityContextOf("user-dirty")));
                scopes.add(holder.bind(
                        DeferredExecutionOrigin.class, DeferredExecutionOrigin.of("delayed-job", "dirty-context")));
                scopes.add(holder.bind(CorrelationCtx.class, new CorrelationCtx(correlation)));
                node.engine.executor().start().onComplete(started);
            });
            await(started.future());
        }

        /** Clears every binding on the context that holds them. */
        void clear() {
            Promise<Void> cleared = Promise.promise();
            context.runOnContext(ignored -> {
                for (int i = scopes.size() - 1; i >= 0; i--) {
                    scopes.get(i).close();
                }
                scopes.clear();
                cleared.complete();
            });
            await(cleared.future());
        }
    }

    /** Approve and ship each write one intent, so the correlation of both paths is observable. */
    static final class ShippingWithIntents extends Definition {
        ShippingWithIntents() {
            super("context-shipping", wf -> {
                wf.initialStep("approve");
                waitFor(wf, "approve", "approve", "approve-intent");
                wf.dispatch("approve-intent", "signal.service", state -> Map.of("ref", state.ref()), "ship");
                waitFor(wf, "ship", "shipped", "ship-intent");
                wf.dispatch("ship-intent", "signal.service", state -> Map.of("ref", state.ref()), "done");
                wf.complete("done");
            });
        }
    }

    /**
     * An order that dispatches an intent on start and another once paid, running the probe in its initial-state
     * callback and in a decision after the paid wait.
     */
    static final class ProbedOrder extends Definition {
        private final SecurityProbe probe;

        ProbedOrder(SecurityProbe probe) {
            super("context-probed", body(probe));
            this.probe = probe;
        }

        private static Consumer<WorkflowBuilder<State>> body(SecurityProbe probe) {
            return wf -> {
                wf.initialStep("start-intent");
                wf.dispatch("start-intent", "signal.service", state -> Map.of("ref", state.ref()), "paid");
                waitFor(wf, "paid", "paid", "paid-probe");
                wf.decide("paid-probe", state -> {
                    probe.observe("paid", state.ref());
                    return "paid-intent";
                });
                wf.dispatch("paid-intent", "signal.service", state -> Map.of("ref", state.ref()), "done");
                wf.complete("done");
            };
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> {
                probe.observe("start", start.ref());
                return new State(start.ref(), List.of(), false, false);
            });
            body(probe).accept(wf);
        }
    }

    /**
     * A fork whose branch {@code a} waits on ack, then runs the probe and writes one intent, and whose branch
     * {@code b} keeps the join open.
     */
    static final class BranchWithIntent extends Definition {
        private final SecurityProbe probe;

        BranchWithIntent(SecurityProbe probe) {
            super("context-branch", body(probe));
            this.probe = probe;
        }

        private static Consumer<WorkflowBuilder<State>> body(SecurityProbe probe) {
            return wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("a", "a-ack", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "b-note", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                waitFor(wf, "a-ack", "ack", "a-probe");
                wf.decide("a-probe", state -> {
                    probe.observe("ack", state.ref());
                    return "a-intent";
                });
                wf.dispatch("a-intent", "signal.service", state -> Map.of("ref", state.ref()), "a-done");
                wf.complete("a-done");
                waitFor(wf, "b-note", "note", "b-done");
                wf.complete("b-done");
                wf.join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin();
                wf.complete("done");
            };
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), List.of(), false, false));
            body(probe).accept(wf);
        }
    }

    /** A fork of a branch that waits on a timer and a branch that waits on a task, each followed by a probe. */
    static final class BranchWithTimerAndTask extends Definition {
        private final SecurityProbe probe;

        BranchWithTimerAndTask(SecurityProbe probe) {
            super("context-branch-timer-task", body(probe));
            this.probe = probe;
        }

        private static Consumer<WorkflowBuilder<State>> body(SecurityProbe probe) {
            return wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("a", "a-timer", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "b-task", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                wf.timer("a-timer", Duration.ofSeconds(60)).toStep("a-probe");
                wf.decide("a-probe", state -> {
                    probe.observe("timer", state.ref());
                    return "a-done";
                });
                wf.complete("a-done");
                wf.task("b-task")
                        .assignToRole("compliance")
                        .decision("approve", Decision.class)
                        .onDecision((state, decision) -> state)
                        .toStep("b-probe")
                        .build();
                wf.decide("b-probe", state -> {
                    probe.observe("task", state.ref());
                    return "b-done";
                });
                wf.complete("b-done");
                wf.join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin();
                wf.complete("done");
            };
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, start -> new State(start.ref(), List.of(), false, false));
            body(probe).accept(wf);
        }
    }
}
