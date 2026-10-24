// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.sleep;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static dev.vertique.workflow.postgresql.executor.FailingTransactions.connectionFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.HeldLock;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Verifies what becomes of a segment that fails: a transient failure retries without spending the attempt, a late
 * failure disposition writes nothing, and a busy instance never makes an executor wait for it.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SegmentFailureIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_segment_failure_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;
    private static final Duration HOLD = Duration.ofSeconds(15);

    /** The ways a segment can fail transiently. */
    enum TransientCase {
        /** A decorator fails the first ten segments with a lost connection. */
        CONNECTION_FAILURE_TEN_TIMES(10),
        /** A trigger fails the first two segments with a real serialization failure. */
        SERIALIZATION_FAILURE_TWICE(2);

        final int failures;

        TransientCase(int failures) {
            this.failures = failures;
        }
    }

    @ParameterizedTest
    @EnumSource(TransientCase.class)
    @DisplayName("transient failures retry without spending attempts")
    void transientFailuresRetryWithoutSpendingAttempts(TransientCase scenario) {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given five attempts, a backoff of 10 to 50 ms, and a start whose first segments fail transiently
            ExecutorFixture.NodeSpec spec = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(5)
                    .transientBackoffMs(10, 50);
            if (scenario == TransientCase.CONNECTION_FAILURE_TEN_TIMES) {
                spec.runner(FailingTransactions.times(scenario.failures, connectionFailure()));
            }
            Node node = spec.build();
            WorkflowInstanceId id = node.record(startOf("transient-1"));
            try (SerializationFailureTrigger trigger = scenario == TransientCase.SERIALIZATION_FAILURE_TWICE
                    ? SerializationFailureTrigger.onFirstWrites(scenario.failures)
                            .installOn(fixture.observer())
                    : null) {

                // When the start executes
                node.startExecutor();
                AtomicBoolean sawDead = new AtomicBoolean();
                awaitCondition("the start to settle", Duration.ofSeconds(45), () -> {
                    String state = fixture.commandOf(id).state();
                    sawDead.compareAndSet(false, "DEAD".equals(state));
                    return "COMPLETE".equals(state) || "DEAD".equals(state);
                });

                // Then it was applied once, was never dead-lettered, and spent one attempt
                CommandRow command = fixture.commandOf(id);
                assertThat(sawDead).isFalse();
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
                assertThat(command.transientCount()).isEqualTo(scenario.failures);
                assertThat(command.claimToken()).isNull();
                assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
                assertThat(fixture.intentsOf(id)).containsExactly("charge");
                List<String> history = fixture.historyOf(id);
                assertThat(Collections.frequency(history, "START")).isEqualTo(1);
                assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
                if (trigger != null) {
                    assertThat(trigger.timesFired()).isGreaterThanOrEqualTo(scenario.failures + 1);
                }
            }
        }
    }

    @Test
    @DisplayName("a failure disposition that runs after the lease was lost writes nothing")
    void failureDispositionAfterLeaseLossWritesNothing() throws Exception {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an initial-state callback that fails once, and a first engine whose failure disposition is held at
            // a gate
            AtomicInteger initialStates = new AtomicInteger();
            fixture.onInitialState = ref -> {
                if (initialStates.getAndIncrement() == 0) {
                    throw new IllegalStateException("the initial state fails once");
                }
            };
            CompletableFuture<Void> atGate = new CompletableFuture<>();
            AtomicReference<Promise<Void>> gate = new AtomicReference<>();
            CommandFaults held = new CommandFaults().beforeFirstDisposition(() -> {
                Promise<Void> promise = ((ContextInternal) Vertx.currentContext()).promise();
                gate.set(promise);
                atGate.complete(null);
                return promise.future();
            });
            Node first = fixture.node("first")
                    .concurrency(1)
                    .leaseSeconds(LEASE_SECONDS)
                    .commands(held.decorator())
                    .build();
            Node second = fixture.node("second").leaseSeconds(LEASE_SECONDS).build();
            WorkflowInstanceId id = first.record(startOf("late-failure"));

            try {
                // When the first engine's segment fails and its disposition waits at the gate past the lease
                first.startExecutor();
                atGate.get(IDLE.toMillis(), TimeUnit.MILLISECONDS);
                awaitCondition("the lease to end", IDLE, () -> fixture.leaseExpired(id));

                // And the second engine claims the command and completes it
                second.startExecutor();
                awaitCondition("the second engine to complete the start", IDLE, () -> "COMPLETE"
                        .equals(fixture.commandOf(id).state()));
                List<String> historyBefore = fixture.historyOf(id);
                assertThat(historyBefore).doesNotContain("FAILED");

                // And the gate opens
                gate.get().complete();
                first.stopExecutor();
            } finally {
                Promise<Void> promise = gate.get();
                if (promise != null) {
                    promise.tryComplete();
                }
            }

            // Then the late disposition wrote nothing: the instance is not failed, the command is complete, and no
            // failure was logged
            assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(command.claimToken()).isNull();
            List<String> history = fixture.historyOf(id);
            assertThat(history).doesNotContain("FAILED");
            assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
            assertThat(fixture.historyOf(id)).isEqualTo(history);
        }
    }

    /** When the caller takes the execution lock of the instance that fails to run. */
    enum BusyCase {
        /** Before the start is claimed. */
        BEFORE_THE_CLAIM,
        /** Between the claim and the segment. */
        BETWEEN_CLAIM_AND_SEGMENT,
        /** Between the failed segment and its failure disposition. */
        BETWEEN_FAILED_SEGMENT_AND_DISPOSITION
    }

    @ParameterizedTest
    @EnumSource(BusyCase.class)
    @DisplayName("a busy instance never holds an executor connection")
    void busyInstanceNeverHoldsAnExecutorConnection(BusyCase scenario) {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an executor pool of two, ten starts of other instances, and a start of X whose lock a caller takes
            CommandFaults faults = new CommandFaults();
            Node node = fixture.node("engine")
                    .leaseSeconds(LEASE_SECONDS)
                    .poolSize(2)
                    .concurrency(4)
                    .commands(faults.decorator())
                    .build();
            if (scenario == BusyCase.BETWEEN_FAILED_SEGMENT_AND_DISPOSITION) {
                fixture.onInitialState = ref -> {
                    if (ref.equals("busy-x")) {
                        throw new IllegalStateException("the initial state of X always fails");
                    }
                };
            }
            WorkflowInstanceId x = node.record(startOf("busy-x"));
            List<WorkflowInstanceId> others = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                others.add(node.record(startOf("other-" + i)));
            }
            AtomicReference<HeldLock> held = new AtomicReference<>();
            AtomicLong heldSince = new AtomicLong();
            Function<WorkflowInstanceId, Future<Void>> hold =
                    id -> fixture.holdExecutionLockAsync(id).map(lock -> {
                        held.set(lock);
                        heldSince.set(System.nanoTime());
                        return null;
                    });
            switch (scenario) {
                case BEFORE_THE_CLAIM -> {
                    held.set(fixture.holdExecutionLock(x));
                    heldSince.set(System.nanoTime());
                }
                case BETWEEN_CLAIM_AND_SEGMENT -> faults.afterClaimOf(x, claim -> hold.apply(x));
                case BETWEEN_FAILED_SEGMENT_AND_DISPOSITION -> faults.beforeFirstDisposition(() -> hold.apply(x));
            }

            try {
                // When the executor runs while the caller holds X for 15 seconds
                node.startExecutor();
                awaitCondition("the caller to hold X", IDLE, () -> held.get() != null);
                boolean claimSpent = scenario != BusyCase.BEFORE_THE_CLAIM;
                boolean released = false;
                boolean othersComplete = false;
                while (System.nanoTime() - heldSince.get() < HOLD.toNanos()) {
                    // Then no executor backend waits for a lock
                    assertThat(fixture.ungrantedAdvisoryLocks()).isZero();

                    // And X's start is never claimed during the hold, or is released with its attempt restored
                    CommandRow command = fixture.commandOf(x);
                    boolean unclaimed = command.state().equals("PENDING")
                            && command.attempts() == 0
                            && command.claimToken() == null;
                    if (!claimSpent || released) {
                        assertThat(unclaimed)
                                .as("X's start while the caller holds X: %s", command)
                                .isTrue();
                    } else if (unclaimed) {
                        released = true;
                    } else {
                        assertThat(command.attempts()).isEqualTo(1);
                    }
                    othersComplete = others.stream()
                            .allMatch(id -> fixture.commandOf(id).state().equals("COMPLETE"));
                    sleep(100);
                }

                // And the ten starts of other instances completed during the hold
                assertThat(othersComplete).isTrue();
                if (claimSpent) {
                    assertThat(released)
                            .as("the claim was released during the hold")
                            .isTrue();
                    assertThat(faults.callsOf("release")).isPositive();
                } else {
                    assertThat(faults.callsOf("release")).isZero();
                }
            } finally {
                HeldLock lock = held.get();
                if (lock != null) {
                    lock.release();
                }
            }

            // When the caller commits, the first two cases run X's start to its first wait, and the third fails again
            // and fails the instance
            if (scenario == BusyCase.BETWEEN_FAILED_SEGMENT_AND_DISPOSITION) {
                awaitCondition("X's start to fail", IDLE, () -> fixture.commandOf(x)
                        .state()
                        .equals("FAILED"));
                assertThat(fixture.instanceOf(x).status()).isEqualTo("FAILED");
            } else {
                awaitCondition("X's start to complete", IDLE, () -> fixture.commandOf(x)
                        .state()
                        .equals("COMPLETE"));
                assertThat(fixture.instanceOf(x).status()).isEqualTo("WAITING");
                assertThat(fixture.commandOf(x).attempts()).isEqualTo(1);
            }
        }
    }
}
