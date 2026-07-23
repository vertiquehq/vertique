// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 *       readers either see a fully-registered workflow or get a clean
 *       {@link WorkflowDefinitionMissingException}; they NEVER see a
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

    @WorkflowContract(definitionId = "task-alpha", definitionVersion = 1)
    interface TaskAlphaContract {}

    @WorkflowContract(definitionId = "task-foo", definitionVersion = 1)
    interface TaskFooV1Contract {}

    @WorkflowContract(definitionId = "task-foo", definitionVersion = 2)
    interface TaskFooV2Contract {}

    @WorkflowContract(definitionId = "task-foo", definitionVersion = 3)
    interface TaskFooV3Contract {}

    // Collision: this contract is used for the "foo-v2 collision" scenario —
    // the v2 definition below reuses this interface to exercise the rollback path.
    @WorkflowContract(definitionId = "task-foo", definitionVersion = 1)
    interface CollidingContractSameAsV1 {}

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

    // Contracts for the 10-definition concurrent-activation test
    @WorkflowContract(definitionId = "id1", definitionVersion = 1)
    interface C1 {}

    @WorkflowContract(definitionId = "id2", definitionVersion = 1)
    interface C2 {}

    @WorkflowContract(definitionId = "id3", definitionVersion = 1)
    interface C3 {}

    @WorkflowContract(definitionId = "id4", definitionVersion = 1)
    interface C4 {}

    @WorkflowContract(definitionId = "id5", definitionVersion = 1)
    interface C5 {}

    @WorkflowContract(definitionId = "id6", definitionVersion = 1)
    interface C6 {}

    @WorkflowContract(definitionId = "id7", definitionVersion = 1)
    interface C7 {}

    @WorkflowContract(definitionId = "id8", definitionVersion = 1)
    interface C8 {}

    @WorkflowContract(definitionId = "id9", definitionVersion = 1)
    interface C9 {}

    @WorkflowContract(definitionId = "id10", definitionVersion = 1)
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
        @DisplayName("10 threads register 10 different definitions; all succeed; allRegistered() returns 10")
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

            Collection<RuntimeWorkflow> allRegistered = registry.allRegistered();
            assertThat(allRegistered).hasSize(10);
        }
    }

    // --- Test 2: Concurrent register + concurrent resolve, same definitionId ---

    @Nested
    @DisplayName("concurrent register and resolve of the same definitionId")
    class ConcurrentRegisterAndResolve {

        @Test
        @DisplayName("readers see either a clean miss or a complete hit; never NPE or unexpected exception")
        void resolveShouldNeverObserveHalfPublishedState() throws InterruptedException {
            WorkflowDefinition<TaskState, ?> def = minimalDef("task-foo", 1, TaskFooV1Contract.class);

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
                            registry.resolveCurrent("task-foo");
                            // Hit — valid: the write completed, we got a fully-registered workflow
                        } catch (WorkflowDefinitionMissingException e) {
                            // Clean miss — valid: registration not yet complete
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

    // --- Test 3: Rollback atomicity ---

    @Nested
    @DisplayName("rollback atomicity on contract collision")
    class RollbackAtomicity {

        @Test
        @DisplayName("failed register due to contract collision leaves no half-committed state")
        void contractCollisionRollsBackVersion() throws InterruptedException {
            // Register "task-foo" v1 with TaskFooV1Contract first.
            registry.register(minimalDef("task-foo", 1, TaskFooV1Contract.class));

            // Now attempt to register "task-bar" v1 using the SAME contract class as "task-foo" v1.
            // This must fail (contract already bound to task-foo). The rollback must ensure
            // task-bar does NOT appear in allRegistered().
            WorkflowDefinition<TaskState, ?> collidingDef = minimalDef("task-bar", 1, TaskFooV1Contract.class);

            assertThatThrownBy(() -> registry.register(collidingDef)).isInstanceOf(WorkflowDefinitionException.class);

            // After the rollback, allRegistered must contain only task-foo v1 — no task-bar
            Collection<RuntimeWorkflow> registered = registry.allRegistered();
            assertThat(registered).hasSize(1);
            assertThat(registered.iterator().next().plan().definitionId()).isEqualTo("task-foo");

            // task-foo v1 is still resolvable; "task-bar" must be missing
            RuntimeWorkflow foo = registry.resolveCurrent("task-foo");
            assertThat(foo.plan().definitionVersion()).isEqualTo(1L);

            assertThatThrownBy(() -> registry.resolveCurrent("task-bar"))
                    .isInstanceOf(WorkflowDefinitionMissingException.class);
        }

        @Test
        @DisplayName("concurrent reader during a rolled-back registration sees only the prior committed state")
        void concurrentReaderSeesOnlyPriorStateAfterRollback() throws InterruptedException {
            // Register "task-foo" v1 first
            registry.register(minimalDef("task-foo", 1, TaskFooV1Contract.class));

            // Now attempt to register "task-foo" v2 with a contract that collides with TaskFooV1Contract
            // (same class — cannot rebind)
            WorkflowDefinition<TaskState, ?> collidingV2 = minimalDef("task-foo", 2, TaskFooV1Contract.class);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger unexpectedCount = new AtomicInteger(0);
            AtomicReference<Throwable> firstUnexpected = new AtomicReference<>();

            // Thread A: attempts to register colliding v2 (will fail with contract collision)
            Future<Void> writerFuture = pool.submit(() -> {
                started.await();
                try {
                    registry.register(collidingV2);
                } catch (WorkflowDefinitionException e) {
                    // Expected — contract collision
                }
                return null;
            });

            // Thread B: concurrently resolves "task-foo"
            Future<Void> readerFuture = pool.submit(() -> {
                started.await();
                long deadline = System.currentTimeMillis() + 200;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        RuntimeWorkflow rw = registry.resolveCurrent("task-foo");
                        // Must be v1 only — v2 never committed successfully
                        if (rw.plan().definitionVersion() != 1L) {
                            if (unexpectedCount.getAndIncrement() == 0) {
                                firstUnexpected.set(
                                        new AssertionError("saw v" + rw.plan().definitionVersion() + " instead of v1"));
                            }
                        }
                    } catch (WorkflowDefinitionMissingException e) {
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

            // After rollback, allRegistered still has exactly 1 entry (task-foo v1 only)
            assertThat(registry.allRegistered()).hasSize(1);
            assertThat(registry.resolveCurrent("task-foo").plan().definitionVersion())
                    .isEqualTo(1L);
        }
    }

    // --- Test 4: Multiple versions of same id sequentially ---

    @Nested
    @DisplayName("multiple versions of the same definitionId registered sequentially")
    class MultipleVersionsSequential {

        @Test
        @DisplayName("resolveCurrent returns v3; resolvePinned returns v1, v2, v3 correctly")
        void shouldResolveCorrectVersions() {
            registry.register(minimalDef("task-foo", 1, TaskFooV1Contract.class));
            registry.register(minimalDef("task-foo", 2, TaskFooV2Contract.class));
            registry.register(minimalDef("task-foo", 3, TaskFooV3Contract.class));

            RuntimeWorkflow current = registry.resolveCurrent("task-foo");
            assertThat(current.plan().definitionVersion()).isEqualTo(3L);

            RuntimeWorkflow v1 = registry.resolvePinned("task-foo", 1L);
            assertThat(v1.plan().definitionVersion()).isEqualTo(1L);

            RuntimeWorkflow v2 = registry.resolvePinned("task-foo", 2L);
            assertThat(v2.plan().definitionVersion()).isEqualTo(2L);

            RuntimeWorkflow v3 = registry.resolvePinned("task-foo", 3L);
            assertThat(v3.plan().definitionVersion()).isEqualTo(3L);

            // Missing version still throws correctly
            assertThatThrownBy(() -> registry.resolvePinned("task-foo", 99L))
                    .isInstanceOf(WorkflowVersionPinUnavailableException.class);
        }
    }
}
