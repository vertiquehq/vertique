// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Concurrency-safety tests for {@link DefaultWorkflowRegistry}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Concurrent registrations of distinct definitionIds all succeed without data loss or
 *       exceptions (FR-WF-DEF-022).</li>
 *   <li>Concurrent register + resolve of the same id never exposes half-published state —
 *       readers either see a fully-registered workflow or find nothing; they NEVER see a
 *       {@link NullPointerException} or {@link WorkflowVersionPinUnavailableException} from
 *       a partially-published-and-then-rolled-back entry (FR-WF-DEF-023).</li>
 *   <li>A failed {@code register()} rolls back atomically — no half-committed state is
 *       observable after the failure (FR-WF-DEF-024).</li>
 *   <li>Multiple versions of the same id registered sequentially resolve correctly.</li>
 * </ul>
 */
class DefaultWorkflowRegistryConcurrencyTest {

    // --- Fixture types ---

    record TaskState(String id) {}

    record StartTask(String id) {}

    @WorkflowContract
    interface TaskAlphaContract {}

    @WorkflowContract
    interface TaskFooContract {}

    // Refusal: the "task-foo" v2 definition below names this contract instead of TaskFooContract,
    // which the registry refuses, to exercise the rollback path.
    @WorkflowContract
    interface OtherTaskContract {}

    // --- Helpers ---

    private static <C> WorkflowDefinition<TaskState, C> minimalDef(String defId, long version, Class<C> contract) {
        return new WorkflowDefinition<>() {
            @Override
            public Class<TaskState> stateType() {
                return TaskState.class;
            }

            @Override
            public Class<C> contract() {
                return contract;
            }

            @Override
            public String definitionId() {
                return defId;
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder<TaskState> wf) {
                wf.init(StartTask.class, cmd -> new TaskState(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    // Two contracts that race for one definition id
    @WorkflowContract("race-a")
    interface RaceAContract {}

    @WorkflowContract("race-b")
    interface RaceBContract {}

    // Contracts for the 10-definition concurrent-activation test
    @WorkflowContract
    interface C1 {}

    @WorkflowContract
    interface C2 {}

    @WorkflowContract
    interface C3 {}

    @WorkflowContract
    interface C4 {}

    @WorkflowContract
    interface C5 {}

    @WorkflowContract
    interface C6 {}

    @WorkflowContract
    interface C7 {}

    @WorkflowContract
    interface C8 {}

    @WorkflowContract
    interface C9 {}

    @WorkflowContract
    interface C10 {}

    private DefaultWorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultWorkflowRegistry();
    }

    // --- Test 1: Concurrent activation, different definitionIds ---

    @Nested
    @DisplayName("concurrent activation of distinct definitionIds")
    class ConcurrentDistinctIds {

        @Test
        @DisplayName("10 threads register 10 different definitions; all succeed; codeDefinitions() returns 10")
        void shouldRegisterAllConcurrently() throws InterruptedException {
            List<WorkflowDefinition<TaskState, ?>> defs = List.of(
                    minimalDef("id1", 1, C1.class),
                    minimalDef("id2", 1, C2.class),
                    minimalDef("id3", 1, C3.class),
                    minimalDef("id4", 1, C4.class),
                    minimalDef("id5", 1, C5.class),
                    minimalDef("id6", 1, C6.class),
                    minimalDef("id7", 1, C7.class),
                    minimalDef("id8", 1, C8.class),
                    minimalDef("id9", 1, C9.class),
                    minimalDef("id10", 1, C10.class));

            ExecutorService pool = Executors.newFixedThreadPool(10);
            CountDownLatch ready = new CountDownLatch(10);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<Void>> futures = new ArrayList<>();
            for (WorkflowDefinition<TaskState, ?> def : defs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    registry.register(def);
                    return null;
                }));
            }

            ready.await();
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // All must complete without exception
            List<Throwable> errors = new ArrayList<>();
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            assertThat(errors)
                    .as("no exceptions during concurrent registration")
                    .isEmpty();

            Collection<RuntimeWorkflow> codeDefinitions = registry.codeDefinitions();
            assertThat(codeDefinitions).hasSize(10);
        }
    }

    // --- Test 2: Concurrent register + concurrent resolve, same definitionId ---

    @Nested
    @DisplayName("concurrent register and resolve of the same definitionId")
    class ConcurrentRegisterAndResolve {

        @Test
        @DisplayName("readers see either a clean miss or a complete hit; never NPE or unexpected exception")
        void resolveShouldNeverObserveHalfPublishedState() throws InterruptedException {
            WorkflowDefinition<TaskState, ?> def = minimalDef("task-foo", 1, TaskFooContract.class);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger unexpectedExceptions = new AtomicInteger(0);
            AtomicReference<Throwable> firstUnexpected = new AtomicReference<>();

            // Thread A: registers "task-foo" v1
            Future<Void> writerFuture = pool.submit(() -> {
                started.await();
                registry.register(def);
                return null;
            });

            // Threads B, C, D: concurrently resolve "task-foo" for 200 ms or 1000 iterations
            List<Future<Void>> readerFutures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                readerFutures.add(pool.submit(() -> {
                    started.await();
                    long deadline = System.currentTimeMillis() + 200;
                    int iterations = 0;
                    while (System.currentTimeMillis() < deadline && iterations < 1000) {
                        iterations++;
                        try {
                            // Hit or clean miss — both valid: the write either completed or has not yet
                            registry.find(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 1L));
                        } catch (NullPointerException | WorkflowVersionPinUnavailableException e) {
                            // Invalid — half-published state leaked through
                            if (unexpectedExceptions.getAndIncrement() == 0) {
                                firstUnexpected.set(e);
                            }
                        } catch (Exception e) {
                            if (unexpectedExceptions.getAndIncrement() == 0) {
                                firstUnexpected.set(e);
                            }
                        }
                    }
                    return null;
                }));
            }

            // Start everything concurrently
            started.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // Writer must complete without exception
            try {
                writerFuture.get();
            } catch (ExecutionException e) {
                throw new AssertionError("writer threw unexpected exception", e.getCause());
            }
            for (Future<Void> rf : readerFutures) {
                try {
                    rf.get();
                } catch (ExecutionException e) {
                    throw new AssertionError("reader threw unexpected exception", e.getCause());
                }
            }

            assertThat(unexpectedExceptions.get())
                    .as("no unexpected exceptions (half-published state leaked): " + firstUnexpected.get())
                    .isZero();
        }
    }

    // --- Test 3: A refused contract ---

    @Nested
    @DisplayName("registrations refused for naming another contract")
    class RefusedContract {

        @Test
        @DisplayName("a refused contract leaves no half-committed state")
        void refusedContractRollsBackVersion() throws InterruptedException {
            // Register "task-foo" v1 with TaskFooContract first.
            registry.register(minimalDef("task-foo", 1, TaskFooContract.class));

            // Now attempt to register "task-foo" v2 naming another contract than v1.
            // This must fail (every version of a definition names one contract). The refusal must ensure
            // v2 does NOT appear in codeDefinitions().
            WorkflowDefinition<TaskState, ?> refusedDef = minimalDef("task-foo", 2, OtherTaskContract.class);

            assertThatThrownBy(() -> registry.register(refusedDef)).isInstanceOf(WorkflowDefinitionException.class);

            // After the refusal, codeDefinitions must contain only task-foo v1
            Collection<RuntimeWorkflow> registered = registry.codeDefinitions();
            assertThat(registered).hasSize(1);
            assertThat(registered.iterator().next().plan().definitionId()).isEqualTo("task-foo");

            // task-foo v1 is still resolvable; v2 must be missing
            RuntimeWorkflow foo = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 1L));
            assertThat(foo.plan().definitionVersion()).isEqualTo(1L);

            assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 2L)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        }

        @Test
        @DisplayName("a second definition id on the same contract is accepted")
        void secondDefinitionIdOnSameContractIsAccepted() {
            registry.register(minimalDef("task-foo", 1, TaskFooContract.class));

            registry.register(minimalDef("task-bar", 1, TaskFooContract.class));

            assertThat(registry.codeDefinitions()).hasSize(2);
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-bar", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("task-bar");
        }

        @Test
        @DisplayName("concurrent reader during a rolled-back registration sees only the prior committed state")
        void concurrentReaderSeesOnlyPriorStateAfterRollback() throws InterruptedException {
            // Register "task-foo" v1 first
            registry.register(minimalDef("task-foo", 1, TaskFooContract.class));

            // Now attempt to register "task-foo" v2 with a contract other than TaskFooContract
            WorkflowDefinition<TaskState, ?> collidingV2 = minimalDef("task-foo", 2, OtherTaskContract.class);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger unexpectedCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstUnexpected = new AtomicReference<>();

            // Thread A: attempts to register v2 under another contract (will be refused)
            Future<Void> writerFuture = pool.submit(() -> {
                started.await();
                try {
                    registry.register(collidingV2);
                } catch (WorkflowDefinitionException e) {
                    // Expected — another contract than v1
                }
                return null;
            });

            // Thread B: concurrently resolves "task-foo"
            Future<Void> readerFuture = pool.submit(() -> {
                started.await();
                long deadline = System.currentTimeMillis() + 200;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        RuntimeWorkflow rw =
                                registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 1L));
                        // Must be v1 only — v2 never committed successfully
                        if (rw.plan().definitionVersion() != 1L) {
                            if (unexpectedCount.getAndIncrement() == 0) {
                                firstUnexpected.set(
                                        new AssertionError("saw v" + rw.plan().definitionVersion() + " instead of v1"));
                            }
                        }
                    } catch (WorkflowVersionPinUnavailableException e) {
                        // Unexpected: v1 was committed before the concurrent attempt started
                        if (unexpectedCount.getAndIncrement() == 0) {
                            firstUnexpected.set(e);
                        }
                    } catch (Exception e) {
                        if (unexpectedCount.getAndIncrement() == 0) {
                            firstUnexpected.set(e);
                        }
                    }
                }
                return null;
            });

            started.countDown();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            try {
                writerFuture.get();
            } catch (ExecutionException e) {
                throw new AssertionError("writer threw unexpected exception", e.getCause());
            }
            try {
                readerFuture.get();
            } catch (ExecutionException e) {
                throw new AssertionError("reader threw unexpected exception", e.getCause());
            }

            assertThat(unexpectedCount.get())
                    .as("reader saw unexpected state: " + firstUnexpected.get())
                    .isZero();

            // After rollback, codeDefinitions still has exactly 1 entry (task-foo v1 only)
            assertThat(registry.codeDefinitions()).hasSize(1);
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 1L))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(1L);
        }
    }

    // --- Test 3b: Two contracts race for one id ---

    @Nested
    @DisplayName("concurrent registrations of one definition id under different contracts")
    class ContractRace {

        private static final int ROUNDS = 300;

        @Test
        @DisplayName("exactly one wins; the other is refused and its contract name is released")
        void exactlyOneContractWins() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < ROUNDS; round++) {
                    // Given: an empty registry and two definitions of one id, each naming another contract
                    DefaultWorkflowRegistry racing = new DefaultWorkflowRegistry();
                    WorkflowDefinition<TaskState, ?> a = minimalDef("race-id", 1, RaceAContract.class);
                    WorkflowDefinition<TaskState, ?> b = minimalDef("race-id", 2, RaceBContract.class);
                    CyclicBarrier start = new CyclicBarrier(2);

                    // When: both register at the same moment from two threads
                    Future<Throwable> first = pool.submit(() -> registerAfter(start, racing, a));
                    Future<Throwable> second = pool.submit(() -> registerAfter(start, racing, b));
                    Throwable aOutcome = first.get(10, TimeUnit.SECONDS);
                    Throwable bOutcome = second.get(10, TimeUnit.SECONDS);

                    // Then: exactly one registration failed, with the registry's definition exception
                    boolean aWins = aOutcome == null;
                    Throwable refused = aWins ? bOutcome : aOutcome;
                    assertThat(aWins ? aOutcome : bOutcome)
                            .as("round %d: the winner", round)
                            .isNull();
                    assertThat(refused)
                            .as("round %d: the loser", round)
                            .isInstanceOf(WorkflowDefinitionException.class);

                    // and: only the winner is registered, and only the winner's contract name is indexed
                    String winnerContract = aWins ? "race-a" : "race-b";
                    String loserContract = aWins ? "race-b" : "race-a";
                    assertThat(racing.codeDefinitions())
                            .as("round %d: registered", round)
                            .hasSize(1);
                    assertThat(racing.codeDefinitions().iterator().next().contractName())
                            .as("round %d: registered contract", round)
                            .isEqualTo(winnerContract);
                    assertThat(racing.typeNames().findContract(winnerContract))
                            .as("round %d: winner's contract name", round)
                            .isPresent();
                    assertThat(racing.typeNames().findContract(loserContract))
                            .as("round %d: loser's contract name", round)
                            .isEmpty();
                }
            } finally {
                pool.shutdownNow();
            }
        }

        private Throwable registerAfter(
                CyclicBarrier start, DefaultWorkflowRegistry target, WorkflowDefinition<TaskState, ?> definition)
                throws Exception {
            start.await(10, TimeUnit.SECONDS);
            try {
                target.register(definition);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }
    }

    // --- Test 4: Multiple versions of same id sequentially ---

    @Nested
    @DisplayName("multiple versions of the same definitionId registered sequentially")
    class MultipleVersionsSequential {

        @Test
        @DisplayName("resolve returns v1, v2, v3 correctly")
        void shouldResolveCorrectVersions() {
            registry.register(minimalDef("task-foo", 1, TaskFooContract.class));
            registry.register(minimalDef("task-foo", 2, TaskFooContract.class));
            registry.register(minimalDef("task-foo", 3, TaskFooContract.class));

            RuntimeWorkflow current = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 3L));
            assertThat(current.plan().definitionVersion()).isEqualTo(3L);

            RuntimeWorkflow v1 = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 1L));
            assertThat(v1.plan().definitionVersion()).isEqualTo(1L);

            RuntimeWorkflow v2 = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 2L));
            assertThat(v2.plan().definitionVersion()).isEqualTo(2L);

            RuntimeWorkflow v3 = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 3L));
            assertThat(v3.plan().definitionVersion()).isEqualTo(3L);

            // Missing version still throws correctly
            assertThatThrownBy(() -> registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "task-foo", 99L)))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        }
    }
}
