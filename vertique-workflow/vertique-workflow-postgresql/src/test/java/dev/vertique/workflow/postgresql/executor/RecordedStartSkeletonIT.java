// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.AWAIT;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CorrelationCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.Tuple;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the walking skeleton of recorded execution: a start is recorded in the caller's transaction and run to its
 * first wait by an executor afterwards, a rolled-back caller leaves nothing, a pending instance counts as live for
 * retirement, and a segment starts from the executor's own context and not from any caller's.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RecordedStartSkeletonIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_start_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("a start is recorded in the caller's transaction and run to its first wait by an executor")
    void startIsRecordedThenRunToItsFirstWaitByAnExecutor() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a plan of a decision, a service dispatch and a wait for payment, and an engine whose executor is
            // stopped
            Node node = fixture.node("engine").build();

            // When a caller records a start in its transaction and commits
            WorkflowInstanceId id = node.record(startOf("order-1"));

            // Then right after the commit the instance is pending with no state, and one start command is pending
            InstanceRow recorded = fixture.instanceOf(id);
            assertThat(recorded).isNotNull();
            assertThat(recorded.status()).isEqualTo("PENDING");
            assertThat(recorded.stateJson()).isNull();
            assertThat(recorded.currentStepId()).isNull();
            assertThat(recorded.businessKey()).isEqualTo("order-1");
            CommandRow command = fixture.commandOf(id);
            assertThat(command.kind()).isEqualTo("START");
            assertThat(command.state()).isEqualTo("PENDING");
            assertThat(command.attempts()).isZero();
            assertThat(command.payload().encode()).contains("order-1");
            assertThat(fixture.rowsOf("workflow_command_queues", id)).isEqualTo(1);
            assertThat(fixture.initialStates)
                    .as("no initial state is computed in the caller")
                    .isEmpty();
            assertThat(fixture.intentCalls)
                    .as("no service call is recorded in the caller")
                    .isEmpty();

            // When the executor starts and the test awaits idle
            node.startExecutor();
            node.awaitIdle(id);

            // Then the instance waits at paid, the intent is in the outbox, and the command is complete
            InstanceRow waiting = fixture.instanceOf(id);
            assertThat(waiting.status()).isEqualTo("WAITING");
            assertThat(waiting.currentStepId()).isEqualTo("paid");
            assertThat(waiting.stateJson()).isNotNull();
            assertThat(fixture.intentsOf(id)).containsExactly("charge");
            CommandRow completed = fixture.commandOf(id);
            assertThat(completed.state()).isEqualTo("COMPLETE");
            assertThat(completed.attempts()).isEqualTo(1);
            assertThat(completed.claimToken()).isNull();
            assertThat(completed.settledAt()).isNotNull();

            // And the history has the initial state and the command entries, each once
            List<String> history = fixture.historyOf(id);
            assertThat(Collections.frequency(history, "START")).isEqualTo(1);
            assertThat(Collections.frequency(history, "COMMAND_RECEIVED")).isEqualTo(1);
            assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a rolled-back caller transaction leaves no instance or command")
    void rolledBackCallerTransactionLeavesNoInstanceOrCommand() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an engine with a running executor
            Node node = fixture.node("engine").build();
            node.startExecutor();

            // When a caller records a start and then rolls its transaction back
            AtomicReference<WorkflowInstanceId> recordedId = new AtomicReference<>();
            Throwable rolledBack = failureOf(
                    node.pool.withTransaction(
                            tx -> node.engine.start(startOf("rolled-back"), tx).compose(id -> {
                                recordedId.set(id);
                                return Future.failedFuture(new IllegalStateException("the caller rolls back"));
                            })),
                    AWAIT);
            ExecutorFixture.sleep(500);

            // Then no instance, command, queue row or dedup row exists
            assertThat(rolledBack).hasMessageContaining("the caller rolls back");
            WorkflowInstanceId id = recordedId.get();
            assertThat(id).isNotNull();
            assertThat(fixture.instanceOf(id)).isNull();
            assertThat(fixture.commandsOf(id)).isEmpty();
            assertThat(fixture.rowsOf("workflow_command_queues", id)).isZero();
            assertThat(fixture.rowsOf("workflow_dedup", id)).isZero();

            // And no segment ran: no initial state was computed and no service call reached a recorder
            assertThat(fixture.initialStates).isEmpty();
            assertThat(fixture.intentCalls).isEmpty();
            assertThat(fixture.intentsOf(id)).isEmpty();
        }
    }

    @Test
    @DisplayName("a pending instance blocks retirement of its version")
    void pendingInstanceBlocksRetirementOfItsVersion() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an available code row of the skeleton v1 in the durable registry
            PgDbExceptionMapper mapper = new PgDbExceptionMapper();
            PgWorkflowDefinitionRegistryStore store =
                    new PgWorkflowDefinitionRegistryStore(fixture.observer(), mapper, Optional.empty());
            DefinitionKey key = fixture.recordAvailable(store, ExecutorFixture.SKELETON);
            Node node = fixture.node("engine").build();

            // And a start of v1 recorded and committed with the executor stopped, so the instance is pending
            StartCommand start = new StartCommand(
                    ExecutorFixture.SKELETON,
                    ExecutorFixture.SkeletonContract.class.getName(),
                    new ExecutorFixture.FlowStart("pinned"),
                    "idem-pinned",
                    "pinned",
                    null,
                    1L,
                    null,
                    null);
            WorkflowInstanceId id = node.record(start);
            assertThat(fixture.instanceOf(id).status()).isEqualTo("PENDING");

            // And v1 deprecated, with its status moved back past the cooling window
            await(store.transition(key, DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED));
            await(fixture.observer()
                    .preparedQuery("UPDATE workflow_definitions SET status_changed_at = status_changed_at"
                            + " - interval '1 day' WHERE definition_id = $1")
                    .execute(Tuple.of(ExecutorFixture.SKELETON)));

            // When v1 is retired through the store, and the instance repository's live count of v1 is read
            Throwable refusal = failureOf(retireThroughStore(store, key), AWAIT);
            long live = await(new PgWorkflowInstanceRepository(fixture.observer(), mapper).countNonTerminal(key));

            // Then retirement fails with the pinned-instances reason and the live count is one
            assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(WorkflowDefinitionLifecycleException.Reason.PINNED_INSTANCES);
                assertThat(failure.key()).isEqualTo(key);
            });
            assertThat(live).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("a start's capture strips the identity snapshot from the instance carrier")
    void startCaptureStripsTheIdentitySnapshotFromTheInstanceCarrier() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given identity carriage installed, an engine with a stopped executor, and an authenticated caller whose
            // durable context holds correlation X, tenant T and an identity snapshot
            Node node = fixture.node("engine").carryContext().build();
            ContextInternal caller = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
            CompletableFuture<WorkflowInstanceId> recorded = new CompletableFuture<>();

            // When the caller records a start in its transaction and commits
            caller.runOnContext(ignored -> fixture.identity()
                    .authenticatedCaller(
                            () -> node.pool.withTransaction(tx -> node.engine.start(startOf("authenticated"), tx)))
                    .onSuccess(recorded::complete)
                    .onFailure(recorded::completeExceptionally));
            WorkflowInstanceId id = await(Future.fromCompletionStage(recorded));

            // Then the stored carrier holds correlation X and tenant T, and no identity snapshot
            DurableMetadata carrier = fixture.instanceCarrierOf(id);
            assertThat(carrier).as("the instance carrier").isNotNull();
            assertThat(carrier.body("correlation").orElseThrow().getString("correlationId"))
                    .isEqualTo(ExecutorFixture.IdentityFixture.CORRELATION);
            assertThat(carrier.body("tenant").orElseThrow().getString("tenantId"))
                    .isEqualTo(ExecutorFixture.IdentityFixture.TENANT);
            assertThat(carrier.has("identity-snapshot")).isFalse();
            assertThat(carrier.namespaces()).containsExactlyInAnyOrder("correlation", "tenant");
        }
    }

    @Test
    @DisplayName("segments run on fresh duplicates of the executor's root context")
    void segmentsRunOnFreshDuplicatesOfTheExecutorRootContext() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a dirty duplicated context with a context-local marker and a bound correlation S, an engine with a
            // stopped executor, and an initial-state callback that records its context, locals and bound correlation
            Node node = fixture.node("engine").carryContext().build();
            ConcurrentMap<String, Seen> seen = new ConcurrentHashMap<>();
            fixture.onInitialState = ref -> {
                ContextInternal current = (ContextInternal) Vertx.currentContext();
                seen.put(
                        ref,
                        new Seen(
                                current,
                                current.getLocal("marker"),
                                fixture.holder().current(CorrelationCtx.class)));
            };
            ContextInternal dirty = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
            ContextInternal caller1 = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
            ContextInternal caller2 = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
            AtomicReference<AutoCloseable> boundCorrelation = new AtomicReference<>();
            try {
                // When the executor is started from the dirty context
                CompletableFuture<Void> started = new CompletableFuture<>();
                dirty.runOnContext(ignored -> {
                    dirty.putLocal("marker", "starter");
                    var scope = fixture.holder().bind(CorrelationCtx.class, new CorrelationCtx("S"));
                    boundCorrelation.set(scope::close);
                    node.engine
                            .executor()
                            .start()
                            .onSuccess(started::complete)
                            .onFailure(started::completeExceptionally);
                });
                await(Future.fromCompletionStage(started));

                // And two callers, each on its own duplicated context, record one start each and commit
                CompletableFuture<WorkflowInstanceId> first = recordOn(caller1, "caller-1", node);
                CompletableFuture<WorkflowInstanceId> second = recordOn(caller2, "caller-2", node);
                WorkflowInstanceId firstId = await(Future.fromCompletionStage(first));
                WorkflowInstanceId secondId = await(Future.fromCompletionStage(second));
                node.awaitIdle(firstId);
                node.awaitIdle(secondId);

                // Then both instances are waiting
                assertThat(fixture.instanceOf(firstId).status()).isEqualTo("WAITING");
                assertThat(fixture.instanceOf(secondId).status()).isEqualTo("WAITING");

                // And each callback ran on a duplicate whose root is the executor verticle's own context, and that is
                // neither the dirty context, nor a caller's, nor the other callback's
                Seen one = seen.get("caller-1");
                Seen other = seen.get("caller-2");
                assertThat(one).isNotNull();
                assertThat(other).isNotNull();
                for (Seen callback : List.of(one, other)) {
                    assertThat(callback.context().isDuplicate()).isTrue();
                    ContextInternal root = callback.context().unwrap();
                    assertThat(root.deploymentID())
                            .as("the verticle's own context")
                            .isNotNull();
                    assertThat(root).isNotSameAs(dirty.unwrap());
                    assertThat(root).isNotSameAs(caller1.unwrap());
                    assertThat(root).isNotSameAs(caller2.unwrap());
                    assertThat(callback.context()).isNotSameAs(dirty);
                    assertThat(callback.context()).isNotSameAs(caller1);
                    assertThat(callback.context()).isNotSameAs(caller2);
                }
                assertThat(one.context()).isNotSameAs(other.context());

                // And no callback saw a marker of the starter or a caller, or correlation S
                for (Seen callback : List.of(one, other)) {
                    assertThat(callback.marker()).isNull();
                    assertThat(callback.correlation()).isEmpty();
                }
            } finally {
                AutoCloseable scope = boundCorrelation.get();
                dirty.runOnContext(ignored -> {
                    dirty.removeLocal("marker");
                    try {
                        if (scope != null) {
                            scope.close();
                        }
                    } catch (Exception ignoredAgain) {
                        // the scope only restores the context
                    }
                });
            }
        }
    }

    // --- Helpers ---

    /** What an initial-state callback saw of its context. */
    private record Seen(ContextInternal context, Object marker, Optional<CorrelationCtx> correlation) {}

    /** Records a start from a caller on the given context, marked with the label, and commits it. */
    private static CompletableFuture<WorkflowInstanceId> recordOn(ContextInternal caller, String ref, Node node) {
        CompletableFuture<WorkflowInstanceId> recorded = new CompletableFuture<>();
        caller.runOnContext(ignored -> {
            caller.putLocal("marker", ref);
            node.pool
                    .withTransaction(tx -> node.engine.start(startOf(ref), tx))
                    .onSuccess(recorded::complete)
                    .onFailure(recorded::completeExceptionally);
        });
        return recorded;
    }

    private static Future<Void> retireThroughStore(PgWorkflowDefinitionRegistryStore store, DefinitionKey key) {
        return store.retire(key);
    }
}
