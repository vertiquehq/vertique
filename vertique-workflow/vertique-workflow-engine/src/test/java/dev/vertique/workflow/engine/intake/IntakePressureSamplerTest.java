// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.workflow.config.WorkflowIntakeConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.IntakePressureChanged;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Verifies the sampler that feeds a node's admission state: the bounded reading it takes, the transitions it reports,
 * its tolerance of failed and hung reads, and the rhythm of its reads.
 *
 * <p>The sampler is deployed as a real verticle with the shortest sample interval the configuration allows, over a
 * command repository whose reading the test controls.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IntakePressureSamplerTest {

    private static final int HIGH = 10;
    private static final int LOW = 4;
    private static final long INTERVAL_MS = WorkflowIntakeConfig.MIN_SAMPLE_INTERVAL_MS;

    private final WorkflowCommandRepository<SqlClient> commands = mock();
    private final IntakeAdmissionState state = new IntakeAdmissionState(HIGH, LOW);
    private final List<WorkflowExecutorEvent> events = new CopyOnWriteArrayList<>();
    private final WorkflowExecutorObserver observer = events::add;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger samplerLog = (Logger) LoggerFactory.getLogger(IntakePressureSampler.class);

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        logs.start();
        samplerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() throws Exception {
        samplerLog.detachAppender(logs);
        logs.stop();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName(
            "reads at most the high mark plus one, refuses from a reading at the high mark and tells the observers once")
    void refusesFromAReadingAtTheHighMark() throws Exception {
        // Given a queue that holds more than the high mark of due commands
        when(commands.countDue(anyLong())).thenReturn(Future.succeededFuture((long) HIGH + 1));

        // When the sampler runs
        deploy();
        await(() -> !state.admitting(), "the node to refuse");

        // Then the read was bounded at one above the high mark, and one event and one warning report the change
        verify(commands, timeout(5_000).atLeastOnce()).countDue(HIGH + 1L);
        await(() -> !events.isEmpty(), "the transition to be reported");
        assertThat(events).containsExactly(new IntakePressureChanged(true, HIGH + 1, false));
        assertThat(warnings()).hasSize(1).allSatisfy(message -> assertThat(message)
                .contains("refused"));
    }

    @Test
    @DisplayName(
            "keeps refusing between the marks and admits again from a reading at the low mark, with one event each way")
    void admitsAgainOnlyFromTheLowMark() throws Exception {
        // Given a queue above the high mark
        AtomicLong due = new AtomicLong(HIGH + 3);
        when(commands.countDue(anyLong())).thenAnswer(call -> Future.succeededFuture(due.get()));
        deploy();
        await(() -> !state.admitting(), "the node to refuse");

        // When the queue falls between the marks and the sampler reads it several times
        due.set(HIGH - 1);
        int reads = readsSoFar();
        await(() -> readsSoFar() >= reads + 3, "three more readings");

        // Then the node still refuses, and falling to the low mark admits it
        assertThat(state.admitting()).isFalse();
        due.set(LOW);
        await(state::admitting, "the node to admit again");
        await(() -> events.size() >= 2, "both transitions to be reported");
        assertThat(events)
                .containsExactly(
                        new IntakePressureChanged(true, HIGH + 1, false), new IntakePressureChanged(false, LOW, false));
        assertThat(warnings()).hasSize(2);
    }

    @Test
    @DisplayName("a refusing node keeps its state through two failed reads, admits on the third and warns once")
    void admitsOnTheThirdFailedRead() throws Exception {
        // Given a refusing node whose reads then fail, each failing read noting whether the node still refused when it
        // began
        AtomicBoolean failing = new AtomicBoolean();
        List<Boolean> admittingAtFailingRead = new CopyOnWriteArrayList<>();
        when(commands.countDue(anyLong())).thenAnswer(call -> {
            if (!failing.get()) {
                return Future.succeededFuture((long) HIGH + 1);
            }
            admittingAtFailingRead.add(state.admitting());
            return Future.failedFuture(new IllegalStateException("pool exhausted"));
        });
        deploy();
        await(() -> !state.admitting(), "the node to refuse");

        // When the reads fail
        failing.set(true);
        await(() -> admittingAtFailingRead.size() >= 6, "six failed reads");

        // Then the node refused when the first three failing reads began, which is after zero, one and two failures,
        // and admitted from the fourth, after the third failure; one event reports it, marked as a failed read
        assertThat(admittingAtFailingRead.subList(0, 4)).containsExactly(false, false, false, true);
        assertThat(events)
                .containsExactly(
                        new IntakePressureChanged(true, HIGH + 1, false),
                        new IntakePressureChanged(false, HIGH + 1, true));
        assertThat(warnings())
                .as("one warning for the refusal and one for the outage, however long the outage lasts")
                .hasSize(2)
                .anySatisfy(message -> assertThat(message).contains("could not be read"));
    }

    @Test
    @DisplayName(
            "a hanging read counts as a failed sample, no second read starts behind it, and a good read restores the rules")
    void aHangingReadIsAFailedSample() throws Exception {
        // Given a refusing node whose next read never completes
        Promise<Long> hanging = Promise.promise();
        AtomicBoolean hang = new AtomicBoolean();
        when(commands.countDue(anyLong()))
                .thenAnswer(call -> hang.get() ? hanging.future() : Future.succeededFuture((long) HIGH + 1));
        deploy();
        await(() -> !state.admitting(), "the node to refuse");
        hang.set(true);
        int reads = readsSoFar();

        // When three sample intervals pass
        await(state::admitting, "the node to admit after three samples without a reading");

        // Then exactly one read was started behind the hang, and the failure was reported once
        Thread.sleep(2 * INTERVAL_MS);
        assertThat(readsSoFar())
                .as("one read is outstanding and no other starts")
                .isLessThanOrEqualTo(reads + 1);
        assertThat(events)
                .contains(new IntakePressureChanged(false, HIGH + 1, true))
                .hasSize(2);

        // And when the hung read finally settles, the next good reading puts the node back under the ordinary rules
        hang.set(false);
        hanging.complete(0L);
        await(() -> events.size() >= 3, "the node to refuse again from a good reading");
        assertThat(state.admitting()).isFalse();
        assertThat(events).last().isEqualTo(new IntakePressureChanged(true, HIGH + 1, false));
    }

    @Test
    @DisplayName(
            "reads again after one interval while the queue is above the low mark and after four once it is at or below")
    void relaxesWhenTheQueueIsQuiet() throws Exception {
        // Given readings that are timed
        List<Long> times = new CopyOnWriteArrayList<>();
        AtomicLong due = new AtomicLong(HIGH + 1);
        when(commands.countDue(anyLong())).thenAnswer(call -> {
            times.add(System.nanoTime());
            return Future.succeededFuture(due.get());
        });

        // When the queue is busy for a few reads and then quiet for a few more
        deploy();
        await(() -> times.size() >= 4, "four busy reads");
        int busy = times.size();
        due.set(0);
        await(() -> times.size() >= busy + 3, "three quiet reads");

        // Then busy reads follow one interval apart and quiet ones four
        long busyGap = (times.get(3) - times.get(1)) / 2;
        long quietGap = (times.get(busy + 2) - times.get(busy + 1));
        assertThat(TimeUnit.NANOSECONDS.toMillis(busyGap)).isLessThan(3 * INTERVAL_MS);
        assertThat(TimeUnit.NANOSECONDS.toMillis(quietGap)).isGreaterThanOrEqualTo(3 * INTERVAL_MS);
    }

    @Test
    @DisplayName("an observer that throws neither stops the others nor the sampling")
    void aThrowingObserverIsIsolated() throws Exception {
        when(commands.countDue(anyLong())).thenReturn(Future.succeededFuture((long) HIGH));
        WorkflowExecutorObserver throwing = event -> {
            throw new IllegalStateException("observer broke");
        };

        deploy(throwing, observer);

        await(() -> !events.isEmpty(), "the second observer to be told");
        verify(commands, timeout(5_000).atLeast(3)).countDue(HIGH + 1L);
        assertThat(events).containsExactly(new IntakePressureChanged(true, HIGH, false));
    }

    @Test
    @DisplayName("stopping the sampler stops its reads")
    void stoppingStopsTheReads() throws Exception {
        when(commands.countDue(anyLong())).thenReturn(Future.succeededFuture(0L));
        String deployment = deploy();
        await(() -> readsSoFar() >= 1, "a first read");

        vertx.undeploy(deployment).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        int reads = readsSoFar();
        Thread.sleep(6 * INTERVAL_MS);

        assertThat(readsSoFar()).isEqualTo(reads);
    }

    @Test
    @DisplayName("a read that settles after the sampler stopped moves no state and reports nothing")
    void aReadSettlingAfterStopMovesNothing() throws Exception {
        // Given a sampler whose first read is still outstanding when it is undeployed
        Promise<Long> outstanding = Promise.promise();
        when(commands.countDue(anyLong())).thenReturn(outstanding.future());
        String deployment = deploy();
        await(() -> readsSoFar() >= 1, "a first read");
        vertx.undeploy(deployment).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // When the read settles above the high mark
        outstanding.complete((long) HIGH + 1);
        Thread.sleep(3 * INTERVAL_MS);

        // Then the node still admits, no transition was reported, and no further read started
        assertThat(state.admitting()).isTrue();
        assertThat(events).isEmpty();
        assertThat(readsSoFar()).isEqualTo(1);
    }

    // --- Helpers ---

    private String deploy(WorkflowExecutorObserver... observers) throws Exception {
        WorkflowIntakeConfig config = new WorkflowIntakeConfig(true, HIGH, LOW, INTERVAL_MS);
        var deployment = IntakePressureSampler.deployment(
                commands,
                config,
                state,
                Set.of(observers.length == 0 ? new WorkflowExecutorObserver[] {observer} : observers));
        return vertx.deployVerticle(deployment.supplier(), deployment.options())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    private int readsSoFar() {
        return (int) org.mockito.Mockito.mockingDetails(commands).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("countDue"))
                .count();
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }
}
