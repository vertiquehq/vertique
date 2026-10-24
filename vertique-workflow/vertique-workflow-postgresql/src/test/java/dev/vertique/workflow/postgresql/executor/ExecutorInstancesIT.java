// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.InstanceRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the executor's deployment and lifecycle: instances run on their own event loops and run each command once,
 * a failing peek does not end the loop, and stop ends claiming and waits for in-flight segments.
 */
@ExtendWith(DatabaseExtension.class)
class ExecutorInstancesIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_instances_it")
            .withMigration("classpath:db/migration/workflow");

    private static final long MARGIN_MS = 1000;
    private static final List<String> SEGMENT_ENTRIES =
            List.of("START", "COMMAND_RECEIVED", "COMMAND_COMPLETED", "SIDE_EFFECT_RECORDED");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("two instances run each command once on different event loops")
    void twoInstancesRunEachCommandOnceOnDifferentEventLoops() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a Vert.x with several event loops, one engine with two executor instances of concurrency one, and
            // an initial-state callback that records its thread and whether it runs on an event-loop context
            // and a gate that returns no claim to its segment until both instances hold one, so that neither can finish
            // its work before the other has taken a command
            ClaimGate gate = new ClaimGate(2);
            Node node = fixture.node("engine")
                    .instances(2)
                    .concurrency(1)
                    .commands(gate.decorator())
                    .build();
            ConcurrentMap<String, Seen> seen = new ConcurrentHashMap<>();
            fixture.onInitialState = ref -> seen.put(
                    ref,
                    new Seen(
                            Thread.currentThread().getName(),
                            Vertx.currentContext() != null
                                    && Vertx.currentContext().isEventLoopContext()
                                    && io.vertx.core.Context.isOnEventLoopThread()));

            // And 20 starts recorded and committed with the executor stopped
            Map<String, WorkflowInstanceId> ids = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                String ref = "spread-" + i;
                ids.put(ref, node.record(startOf(ref)));
            }

            // When the executor starts and the test awaits idle for all 20
            node.startExecutor();
            for (WorkflowInstanceId id : ids.values()) {
                node.awaitIdle(id);
            }

            // Then every start ran exactly once
            Map<String, Set<String>> threadsByClaimant = new HashMap<>();
            Map<String, Integer> segmentsByClaimant = new HashMap<>();
            for (Map.Entry<String, WorkflowInstanceId> entry : ids.entrySet()) {
                CommandRow command = fixture.commandOf(entry.getValue());
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
                List<String> history = fixture.historyOf(entry.getValue());
                assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);
                assertThat(Collections.frequency(history, "START")).isEqualTo(1);

                // And every segment ran on an event-loop context, never a worker thread
                Seen segment = seen.get(entry.getKey());
                assertThat(segment).isNotNull();
                assertThat(segment.eventLoop())
                        .as("segment of %s", entry.getKey())
                        .isTrue();
                assertThat(segment.thread()).doesNotContain("worker");
                threadsByClaimant
                        .computeIfAbsent(command.claimedBy(), claimant -> new HashSet<>())
                        .add(segment.thread());
                segmentsByClaimant.merge(command.claimedBy(), 1, Integer::sum);
            }
            assertThat(fixture.initialStates).hasSize(20).doesNotHaveDuplicates();

            // And the deployment has two verticle instances with distinct claimants, the node id plus -0 and -1
            assertThat(threadsByClaimant.keySet()).hasSize(2);
            List<String> claimants = new ArrayList<>(threadsByClaimant.keySet());
            Collections.sort(claimants);
            assertThat(claimants.get(0)).endsWith("-0");
            assertThat(claimants.get(1)).endsWith("-1");
            assertThat(claimants.get(0).substring(0, claimants.get(0).length() - 2))
                    .isEqualTo(claimants.get(1).substring(0, claimants.get(1).length() - 2));

            // And both instances ran at least one segment, on thread sets that are disjoint
            assertThat(segmentsByClaimant.values()).allMatch(count -> count >= 1);
            Set<String> firstThreads = threadsByClaimant.get(claimants.get(0));
            Set<String> secondThreads = threadsByClaimant.get(claimants.get(1));
            assertThat(firstThreads).doesNotContainAnyElementsOf(secondThreads);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a throwing peek does not stop the loop")
    void throwingPeekDoesNotStopTheLoop() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a poll interval of 100 ms and a command repository whose peek throws on its first call, returns
            // no future on its second and a failed future on its third, and then delegates
            AtomicInteger peeks = new AtomicInteger();
            Node node = fixture.node("engine")
                    .pollIntervalMs(100)
                    .commands(ExecutorFixture.intercepting("peek", (call, member, arguments, real) -> {
                        peeks.incrementAndGet();
                        return switch (call) {
                            case 1 -> throw new IllegalStateException("the first peek throws");
                            case 2 -> null;
                            case 3 -> Future.failedFuture(new IllegalStateException("the third peek fails"));
                            default -> real.call();
                        };
                    }))
                    .build();

            // And one start recorded and committed with the executor stopped
            String sentinel = "ZZPAYLOAD";
            WorkflowInstanceId id = node.record(startOf(sentinel));

            // When the executor starts and the test awaits idle
            node.startExecutor();
            await(node.engine.executionProbe().awaitIdle(id, java.time.Duration.ofSeconds(5)));

            // Then the instance waits at its first wait and the start command is complete after one attempt
            InstanceRow waiting = fixture.instanceOf(id);
            assertThat(waiting.status()).isEqualTo("WAITING");
            assertThat(waiting.currentStepId()).isEqualTo("paid");
            CommandRow command = fixture.commandOf(id);
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(command.attempts()).isEqualTo(1);

            // And the decorator saw at least four peeks and three warnings were logged, none with a payload
            assertThat(peeks.get()).isGreaterThanOrEqualTo(4);
            List<ILoggingEvent> warnings = fixture.logged(Level.WARN);
            assertThat(warnings).hasSize(3);
            for (ILoggingEvent warning : warnings) {
                assertThat(warning.getFormattedMessage()).doesNotContain(sentinel);
            }

            // And the verticle instance is still deployed
            assertThat(fixture.vertx().deploymentIDs()).hasSize(1);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("stop ends claiming and completes when in-flight segments commit or hit their deadline")
    void stopEndsClaimingAndCompletesWhenInFlightSegmentsCommitOrHitTheirDeadline() {
        // First run: the stalled segment commits after stop began
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a lease of five seconds, an executor of concurrency one, and a gate armed by the initial-state
            // callback so that the segment stalls after the execution lock is held
            fixture.onInitialState = ref -> fixture.gate.armOnCall(1);
            Node node = fixture.node("engine")
                    .leaseSeconds(5)
                    .pollIntervalMs(100)
                    .concurrency(1)
                    .build();
            WorkflowInstanceId a = node.record(startOf("stop-a"));
            node.startExecutor();
            await(Future.fromCompletionStage(fixture.gate.stalled()), IDLE);

            // And start B recorded and committed after A's claim
            WorkflowInstanceId b = node.record(startOf("stop-b"));

            // When stop is called and the gate is opened one second later
            Future<Void> stop = node.engine.executor().stop();
            ExecutorFixture.sleep(1000);
            assertThat(stop.isComplete())
                    .as("stop waits for the stalled segment")
                    .isFalse();
            fixture.gate.open();
            await(stop);
            // An executor that kept claiming after stop began would take B within a few milliseconds of A's slot
            // freeing
            ExecutorFixture.sleep(500);

            // Then stop completed only after A is complete, and B was not claimed after stop began
            assertThat(fixture.commandOf(a).state()).isEqualTo("COMPLETE");
            CommandRow notClaimed = fixture.commandOf(b);
            assertThat(notClaimed.state()).isEqualTo("PENDING");
            assertThat(notClaimed.attempts()).isZero();
            assertThat(notClaimed.claimedBy()).isNull();
        }

        // Second run: the stalled segment hits its deadline while stop waits
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given the same, and the gate staying closed until a fresh executor has run A
            fixture.onInitialState = ref -> fixture.gate.armOnCall(1);
            Node node = fixture.node("engine")
                    .leaseSeconds(5)
                    .pollIntervalMs(100)
                    .concurrency(1)
                    .build();
            long clockOffset = fixture.databaseClockOffsetMillis();
            WorkflowInstanceId a = node.record(startOf("stop-a"));
            node.startExecutor();
            await(Future.fromCompletionStage(fixture.gate.stalled()), IDLE);
            CommandRow claimed = fixture.commandOf(a);
            long leaseEndMs = claimed.leaseUntil().toInstant().toEpochMilli() - clockOffset;
            node.record(startOf("stop-b"));

            // When stop is called
            Future<Void> stop = node.engine.executor().stop();
            await(stop, java.time.Duration.ofSeconds(10));
            long stoppedAtMs = System.currentTimeMillis();

            // Then stop completed by A's own deadline, before A's lease ended, with A's segment rolled back
            assertThat(stoppedAtMs).as("stop completed before the lease ended").isLessThan(leaseEndMs);
            assertThat(stoppedAtMs).as("stop waited for the deadline").isGreaterThan(leaseEndMs - MARGIN_MS - 300);
            assertThat(fixture.holdersOfX(a)).isEmpty();
            assertThat(fixture.instanceOf(a).stateJson()).isNull();
            assertThat(fixture.intentsOf(a)).isEmpty();
            assertThat(fixture.historyOf(a)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);
            CommandRow rolledBack = fixture.commandOf(a);
            assertThat(rolledBack.state()).isEqualTo("CLAIMED");
            assertThat(rolledBack.attempts()).isEqualTo(1);

            // And a fresh executor started after the lease ended claims A with the second attempt and applies it once
            ExecutorFixture.sleep(Math.max(0, leaseEndMs + 200 - System.currentTimeMillis()));
            Node fresh =
                    fixture.node("fresh").leaseSeconds(5).pollIntervalMs(100).build();
            fresh.startExecutor();
            awaitCondition("A to complete", IDLE, () -> "COMPLETE"
                    .equals(fixture.commandOf(a).state()));
            assertThat(fixture.commandOf(a).attempts()).isEqualTo(2);
            assertThat(fixture.intentsOf(a)).containsExactly("charge");
            List<String> history = fixture.historyOf(a);
            assertThat(Collections.frequency(history, "START")).isEqualTo(1);
            assertThat(Collections.frequency(history, "COMMAND_COMPLETED")).isEqualTo(1);

            // And opening the gate afterwards commits nothing
            fixture.gate.open();
            ExecutorFixture.sleep(500);
            assertThat(fixture.intentsOf(a)).containsExactly("charge");
            assertThat(fixture.historyOf(a)).isEqualTo(history);
            assertThat(fixture.commandOf(a).state()).isEqualTo("COMPLETE");
        }
    }

    /** What an initial-state callback saw of the thread it ran on. */
    private record Seen(String thread, boolean eventLoop) {}

    /**
     * Holds the result of every successful claim until the given number of distinct claimants have each claimed a
     * command, then lets all of them go and passes every later claim straight through. Which instance wins which
     * command is left to the database, but every instance takes part.
     */
    private static final class ClaimGate {
        private final int claimants;
        private final Set<String> seen = new HashSet<>();
        private final List<Promise<Void>> held = new ArrayList<>();
        private boolean open;

        ClaimGate(int claimants) {
            this.claimants = claimants;
        }

        @SuppressWarnings("unchecked")
        UnaryOperator<WorkflowCommandRepository<SqlClient>> decorator() {
            return real -> (WorkflowCommandRepository<SqlClient>) Proxy.newProxyInstance(
                    WorkflowCommandRepository.class.getClassLoader(),
                    new Class<?>[] {WorkflowCommandRepository.class},
                    (proxy, method, arguments) -> {
                        Object result;
                        try {
                            result = method.invoke(real, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if (!method.getName().equals("claim")) {
                            return result;
                        }
                        String claimant = (String) arguments[1];
                        return ((Future<Optional<ClaimedCommand>>) result)
                                .compose(claimed -> claimed.isPresent()
                                        ? hold(claimant, claimed)
                                        : Future.succeededFuture(claimed));
                    });
        }

        private <T> Future<T> hold(String claimant, T result) {
            Promise<Void> promise;
            synchronized (this) {
                seen.add(claimant);
                if (open || seen.size() >= claimants) {
                    open = true;
                    List<Promise<Void>> release = new ArrayList<>(held);
                    held.clear();
                    release.forEach(Promise::tryComplete);
                    return Future.succeededFuture(result);
                }
                promise = ((ContextInternal) Vertx.currentContext()).promise();
                held.add(promise);
            }
            return promise.future().map(result);
        }
    }
}
