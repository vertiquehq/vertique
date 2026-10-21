// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Verifies that every public engine entrypoint that accepts a caller-supplied SQL client rejects a
 * client that is not a connection with a pending Vert.x transaction, as a failed future and before
 * any SQL-capable collaborator is reached.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowTransactionClientGuardTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "client-guard-flow";
    private static final String SIGNAL_NAME = "approve";

    private static final WorkflowInstanceId INSTANCE = new WorkflowInstanceId(UUID.randomUUID());
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID TIMER_ID = UUID.randomUUID();
    private static final WorkflowActor ACTOR = new WorkflowActor.User("alice");
    private static final DurableMetadata CARRIER =
            DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "CARRIER"));
    private static final StartCommand START = new StartCommand(
            DEFINITION_ID,
            Object.class.getName(),
            Map.of(),
            "start-" + UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null);

    /** Raised by every SQL-capable collaborator mock, so a reached boundary is visible as a failure. */
    private static final class SqlBoundaryReached extends RuntimeException {
        SqlBoundaryReached(String method) {
            super("SQL boundary reached: " + method);
        }
    }

    @FunctionalInterface
    private interface EngineCall {
        Future<?> call(WorkflowEngineHandle engine, SqlClient client);
    }

    /** One public transaction-aware overload, invoked with the client under test. */
    private record EngineOperation(String name, EngineCall call) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** One category of client that is not a connection with a pending Vert.x transaction. */
    private record InvalidClient(String name, Supplier<SqlClient> factory) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Harness(
            WorkflowEngineHandle engine,
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore) {}

    static Stream<Arguments> operationsAndInvalidClients() {
        List<EngineOperation> operations = List.of(
                new EngineOperation("start", (engine, client) -> engine.start(START, client)),
                new EngineOperation(
                        "signal with instance scope",
                        (engine, client) -> engine.signal(INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", client)),
                new EngineOperation(
                        "signal with null branch scope",
                        (engine, client) ->
                                engine.signal(INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", null, null, client)),
                new EngineOperation(
                        "signal with branch scope",
                        (engine, client) ->
                                engine.signal(INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", "fork", "branch", client)),
                new EngineOperation(
                        "signal with null carrier",
                        (engine, client) ->
                                engine.signal(INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", null, null, null, client)),
                new EngineOperation(
                        "signal with instance carrier",
                        (engine, client) -> engine.signal(
                                INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", null, null, CARRIER, client)),
                new EngineOperation(
                        "signal with branch carrier",
                        (engine, client) -> engine.signal(
                                INSTANCE, SIGNAL_NAME, Map.of(), "signal-key", "fork", "branch", CARRIER, client)),
                new EngineOperation("cancel", (engine, client) -> engine.cancel(INSTANCE, "reason", client)),
                new EngineOperation("retry", (engine, client) -> engine.retry(INSTANCE, client)),
                new EngineOperation("migrate", (engine, client) -> engine.migrate(INSTANCE, 2L, client)),
                new EngineOperation(
                        "taskCompleted",
                        (engine, client) -> engine.taskCompleted(
                                new TaskCompletionCommand(TASK_ID, "approve", Map.of(), "task-key", ACTOR, null),
                                client)),
                new EngineOperation("taskDueFired", (engine, client) -> engine.taskDueFired(INSTANCE, TASK_ID, client)),
                new EngineOperation(
                        "taskReassigned",
                        (engine, client) -> engine.taskReassigned(
                                new TaskReassignmentCommand(
                                        TASK_ID, new TaskAssignment.User("bob"), ACTOR, "task-key", null),
                                client)),
                new EngineOperation(
                        "taskReminderFired",
                        (engine, client) -> engine.taskReminderFired(INSTANCE, TASK_ID, TIMER_ID, FIXED_NOW, client)),
                new EngineOperation("timerFired", (engine, client) -> engine.timerFired(INSTANCE, TIMER_ID, client)),
                new EngineOperation(
                        "timerFiringFailed",
                        (engine, client) ->
                                engine.timerFiringFailed(INSTANCE, TIMER_ID, "ERROR", "failure message", client)));

        List<InvalidClient> clients = List.of(
                new InvalidClient("a pool", () -> mock(Pool.class)),
                new InvalidClient("a bare connection", WorkflowTransactionClientGuardTest::bareConnection),
                new InvalidClient("a connection whose transaction completed", () -> {
                    return connectionWithTransaction(Future.succeededFuture());
                }),
                new InvalidClient("a connection with a raw BEGIN", WorkflowTransactionClientGuardTest::rawBegin),
                new InvalidClient("an opaque wrapper", WorkflowTransactionClientGuardTest::opaqueWrapper));

        return operations.stream()
                .flatMap(operation -> clients.stream().map(client -> Arguments.of(operation, client)));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("operationsAndInvalidClients")
    void rejectsInvalidClientBeforeEveryEngineSqlBoundary(EngineOperation operation, InvalidClient invalid) {
        // Given an engine with no definition store whose SQL-capable collaborators fail loudly when reached
        Harness harness = harness();
        // And a client that is not a connection with a pending Vert.x transaction
        SqlClient client = invalid.factory().get();

        // When the public transaction-aware overload is invoked with that client
        Future<?> outcome;
        try {
            outcome = operation.call().call(harness.engine(), client);
        } catch (Throwable thrown) {
            throw new AssertionError(operation + " must return a failed future, not throw synchronously", thrown);
        }

        // Then the future fails with an IllegalArgumentException
        Throwable failure = failureOf(outcome);
        assertThat(failure)
                .as("%s with %s must fail the returned future", operation, invalid)
                .isInstanceOf(IllegalArgumentException.class);

        // And no SQL-capable collaborator was reached
        verifyNoInteractions(
                harness.txRunner(),
                harness.instances(),
                harness.history(),
                harness.dedup(),
                harness.branchTokens(),
                harness.joinStates(),
                harness.timerStore(),
                harness.taskStore());
    }

    /** A connection that reports no Vert.x transaction. */
    private static SqlClient bareConnection() {
        return mock(SqlConnection.class);
    }

    /** A connection that exposes the given completion future as its Vert.x transaction. */
    private static SqlConnection connectionWithTransaction(Future<Void> completion) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.completion()).thenReturn(completion);
        SqlConnection connection = mock(SqlConnection.class);
        when(connection.transaction()).thenReturn(transaction);
        return connection;
    }

    /**
     * A connection on which the caller issued a textual BEGIN, so the database is inside a
     * transaction while the Vert.x connection knows of none.
     */
    @SuppressWarnings("unchecked")
    private static SqlClient rawBegin() {
        SqlConnection connection = mock(SqlConnection.class);
        io.vertx.sqlclient.Query<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> begin =
                mock(io.vertx.sqlclient.Query.class);
        when(connection.query("BEGIN")).thenReturn(begin);
        when(begin.execute()).thenReturn(Future.succeededFuture());
        connection.query("BEGIN").execute();
        Mockito.clearInvocations(connection, begin);
        return connection;
    }

    /** A client that delegates every call to a connection with a pending transaction but is not a connection. */
    private static SqlClient opaqueWrapper() {
        SqlConnection active = connectionWithTransaction(Promise.<Void>promise().future());
        return (SqlClient) Proxy.newProxyInstance(
                WorkflowTransactionClientGuardTest.class.getClassLoader(),
                new Class<?>[] {SqlClient.class},
                (proxy, method, args) -> {
                    try {
                        return method.invoke(active, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Harness harness() {
        WorkflowTransactionRunner<SqlClient> txRunner = boundaryMock(WorkflowTransactionRunner.class);
        WorkflowInstanceRepository<SqlClient> instances = boundaryMock(WorkflowInstanceRepository.class);
        WorkflowHistoryRepository<SqlClient> history = boundaryMock(WorkflowHistoryRepository.class);
        WorkflowDedupRepository<SqlClient> dedup = boundaryMock(WorkflowDedupRepository.class);
        BranchTokenRepository<SqlClient> branchTokens = boundaryMock(BranchTokenRepository.class);
        JoinStateRepository<SqlClient> joinStates = boundaryMock(JoinStateRepository.class);
        TimerStore<SqlClient> timerStore = boundaryMock(TimerStore.class);
        TaskStore<SqlClient> taskStore = boundaryMock(TaskStore.class);

        RuntimeWorkflow runtime = runtimeWorkflow();
        WorkflowRegistry registry = mock(WorkflowRegistry.class);
        DefinitionKey currentKey = new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, 1);
        when(registry.keysFor(DEFINITION_ID)).thenReturn(List.of(currentKey));
        when(registry.isDeployed(currentKey)).thenReturn(true);
        when(registry.resolve(any(DefinitionKey.class))).thenReturn(runtime);

        DefaultContextHolder holder = new DefaultContextHolder();
        Set<DurableContextMetadataDecoder<?>> decoders = Set.of(TenantCtxCodec.decoder());
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(TenantCtxCodec.encoder()), decoders),
                holder,
                new ContextScopeBinder(holder));

        WorkflowMigrationRegistry migrations = new WorkflowMigrationRegistry() {
            @Override
            public Optional<WorkflowMigrationHandler<?, ?>> find(
                    String sourceScope,
                    String definitionId,
                    long sourceVersion,
                    String targetScope,
                    long targetVersion) {
                return Optional.empty();
            }

            @Override
            public Collection<WorkflowMigrationHandler<?, ?>> all() {
                return List.of();
            }
        };

        WorkflowEngineHandle engine = WorkflowEngineFactory.create(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                FIXED_CLOCK,
                Set.<WorkflowSideEffectRecorder<SqlClient>>of(),
                WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
                Optional.of(migrations),
                propagator);
        return new Harness(
                engine, txRunner, instances, history, dedup, branchTokens, joinStates, timerStore, taskStore);
    }

    /** A mock whose every future-returning method fails, so reaching any SQL boundary is observable. */
    @SuppressWarnings("unchecked")
    private static <T> T boundaryMock(Class<?> type) {
        return (T) Mockito.mock(type, invocation -> {
            if (Future.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return Future.failedFuture(new SqlBoundaryReached(
                        type.getSimpleName() + "." + invocation.getMethod().getName()));
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static RuntimeWorkflow runtimeWorkflow() {
        WaitSignalNode waitSignal = new WaitSignalNode(
                "wait-signal", SIGNAL_NAME, Object.class.getName(), new CallbackId("state-updater"), "complete", null);
        List<WorkflowNode> nodes = List.of(waitSignal, new CompleteNode("complete"));
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1, "plan-hash", Object.class.getName(), waitSignal.stepId(), nodes, null);
        WorkflowCallbackRegistry callbacks = mock(WorkflowCallbackRegistry.class);
        when(callbacks.stateUpdater(new CallbackId("state-updater"))).thenReturn((state, payload) -> Map.of());
        return RuntimeWorkflow.of(
                plan, "test-contract", Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /** Returns the failure of the future, or {@code null} when it succeeded; never waits unboundedly. */
    private static Throwable failureOf(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting a future", e);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("A future did not complete within five seconds", e);
        }
    }
}
