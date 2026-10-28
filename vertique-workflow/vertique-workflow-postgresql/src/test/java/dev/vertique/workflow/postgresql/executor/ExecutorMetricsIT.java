// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.micrometer.workflow.MicrometerWorkflowModule;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the Micrometer bridge, handed to the executors as their observer set, counts the commands that
 * complete, defer and dead-letter on managed PostgreSQL, and reports the dead-lettered command in its gauge.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExecutorMetricsIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_metrics_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int LEASE_SECONDS = 5;

    @Test
    @DisplayName("the bridge counts completed, deferred and dead-lettered commands")
    void bridgeCountsCompletedDeferredAndDeadLetteredCommands() {
        // Given the bridge over a simple registry, as the observer set of every executor
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Set<WorkflowExecutorObserver> bridge =
                DaggerExecutorMetricsIT_TestComponent.factory().create(registry).executorObservers();
        WorkflowExecutorObserver[] observers = bridge.toArray(new WorkflowExecutorObserver[0]);
        assertThat(observers).hasSize(1);

        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // And engine B that registers the definition and engine A that does not, one attempt per command
            CommandFaults faults = new CommandFaults();
            Node b = fixture.node("B")
                    .leaseSeconds(LEASE_SECONDS)
                    .maxAttempts(1)
                    .observers(observers)
                    .commands(faults.decorator())
                    .build();
            Node a = fixture.node("A")
                    .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                    .pollIntervalMs(100)
                    .observers(observers)
                    .build();

            // And three starts recorded: two that complete and one that B's executor abandons as a crash would
            WorkflowInstanceId first = b.record(startOf("first"));
            WorkflowInstanceId second = b.record(startOf("second"));
            WorkflowInstanceId crashes = b.record(startOf("crashes"));
            faults.crashEverySegmentOf(crashes);
            List<WorkflowInstanceId> all = List.of(first, second, crashes);

            // When A's executor peeks and defers the starts it cannot run
            a.startExecutor();
            awaitCondition("A to defer the starts", IDLE, () -> all.stream()
                    .allMatch(id -> "DEFERRED".equals(fixture.commandOf(id).state())));
            a.stopExecutor();

            // And B's executor runs the starts, their backoff elapsed, until they settle
            all.forEach(id -> fixture.fastForward(fixture.commandOf(id).id()));
            b.startExecutor();
            awaitCondition(
                    "the starts to settle",
                    Duration.ofSeconds(40),
                    () -> fixture.commandOf(first).state().equals("COMPLETE")
                            && fixture.commandOf(second).state().equals("COMPLETE")
                            && fixture.commandOf(crashes).state().equals("DEAD"));
            awaitCondition("the dead gauge to read the dead-lettered command", IDLE, () -> {
                Gauge dead = registry.find("vertique.workflow.commands.dead").gauge();
                return dead != null && dead.value() == 1.0;
            });
            b.stopExecutor();

            // Then the completed starts, the deferral and the dead letter are counted
            assertThat(meterCount(registry, "vertique.workflow.commands.settled", "start", "complete"))
                    .isEqualTo(2.0);
            assertThat(deferralCount(registry, "start")).isGreaterThanOrEqualTo(1.0);
            assertThat(meterCount(registry, "vertique.workflow.commands.settled", "start", "dead_lettered"))
                    .isEqualTo(1.0);

            // And the dead gauge reads the one dead-lettered command
            assertThat(registry.get("vertique.workflow.commands.dead").gauge().value())
                    .isEqualTo(1.0);

            // And the meter counts equal the settled outcomes of the command rows
            List<CommandRow> rows = all.stream().map(fixture::commandOf).toList();
            assertThat(rows.stream()
                            .filter(row -> row.state().equals("COMPLETE"))
                            .count())
                    .isEqualTo((long) meterCount(registry, "vertique.workflow.commands.settled", "start", "complete"));
            assertThat(rows.stream().filter(row -> row.state().equals("DEAD")).count())
                    .isEqualTo((long)
                            meterCount(registry, "vertique.workflow.commands.settled", "start", "dead_lettered"));
        }
    }

    private static double meterCount(MeterRegistry registry, String name, String kind, String outcome) {
        Counter counter =
                registry.find(name).tag("kind", kind).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static double deferralCount(MeterRegistry registry, String kind) {
        Counter counter = registry.find("vertique.workflow.commands.deferred")
                .tag("kind", kind)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Singleton
    @Component(modules = {MicrometerWorkflowModule.class, ObserverSetModule.class})
    interface TestComponent {
        Set<WorkflowExecutorObserver> executorObservers();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance MeterRegistry registry);
        }
    }

    @Module
    abstract static class ObserverSetModule {
        @Multibinds
        abstract Set<WorkflowExecutorObserver> executorObservers();
    }
}
