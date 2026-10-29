// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandDeferred;
import dev.vertique.workflow.ops.WorkflowExecutionProbe.IdleReport;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.LookupSpy;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.ExecutorObserverIT.RecordingExecutorObserver;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an executor defers a command whose plan its node cannot run instead of claiming it, that another node
 * runs it, that a head nobody can run is deferred again with a rising count and no spent attempt, that a stale or
 * racing deferral writes nothing or once, and that a command on an ended instance is settled without any plan lookup.
 */
@ExtendWith(DatabaseExtension.class)
class CommandDeferralIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_deferral_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String PAYLOAD_MARKER = "zz-payload-marker";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an executor lacking the code defers the command and another executor runs it")
    void executorLackingTheCodeDefersAndAnotherRunsIt() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given engine B that holds the skeleton workflow and engine A that does not, B's executor stopped, and a
            // start recorded on B
            Node b = fixture.node("B").build();
            Node a = fixture.node("A")
                    .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                    .pollIntervalMs(100)
                    .build();
            WorkflowInstanceId id = b.record(startOf("lacks-the-code"));

            // When A's executor peeks
            OffsetDateTime before = fixture.databaseNow();
            a.startExecutor();
            awaitCondition("A to defer the start", IDLE, () -> "DEFERRED"
                    .equals(fixture.commandOf(id).state()));
            OffsetDateTime after = fixture.databaseNow();
            a.stopExecutor();

            // Then the command is deferred once, with no attempt spent, five seconds ahead of the deferral
            CommandRow deferred = fixture.commandOf(id);
            assertThat(deferred.state()).isEqualTo("DEFERRED");
            assertThat(deferred.deferralCount()).isEqualTo(1);
            assertThat(deferred.attempts()).isZero();
            assertThat(deferred.claimToken()).isNull();
            assertThat(deferred.availableAt()).isAfterOrEqualTo(before.plusSeconds(5));
            assertThat(deferred.availableAt()).isBeforeOrEqualTo(after.plusSeconds(5));
            assertThat(fixture.instanceOf(id).status()).isEqualTo("PENDING");

            // When B's backoff has elapsed and B's executor starts
            fixture.fastForward(deferred.id());
            b.startExecutor();
            b.awaitIdle(id);

            // Then the instance is at its first wait and the command spent one attempt, on B
            assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
            assertThat(fixture.instanceOf(id).currentStepId()).isEqualTo("paid");
            CommandRow completed = fixture.commandOf(id);
            assertThat(completed.state()).isEqualTo("COMPLETE");
            assertThat(completed.attempts()).isEqualTo(1);
            assertThat(completed.deferralCount()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a head nobody can run is deferred again with a rising count and no spent attempt")
    void headNobodyCanRunIsReDeferredWithRisingCountAndNoAttempt() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given one executor without the plan, a stuck age of one second, a recording observer and a start
            // recorded on another engine
            RecordingExecutorObserver recording = new RecordingExecutorObserver();
            Node recorder = fixture.node("recorder").build();
            Node a = fixture.node("A")
                    .withRegistry(fixture.registryWithout(ExecutorFixture.SKELETON))
                    .pollIntervalMs(100)
                    .stuckAfterSeconds(1)
                    .observers(recording)
                    .build();
            WorkflowInstanceId id = recorder.record(startOf(PAYLOAD_MARKER));

            // When three backoff cycles run, each fast-forwarded by setting the availability to now
            a.startExecutor();
            List<Duration> backoffs = List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
            for (int cycle = 1; cycle <= 3; cycle++) {
                int expectedCount = cycle;
                awaitCondition("deferral " + cycle, IDLE, () -> {
                    CommandRow row = fixture.commandOf(id);
                    return "DEFERRED".equals(row.state()) && row.deferralCount() == expectedCount;
                });

                // Then the backoff after the deferral is the one of the count before it, and no attempt is spent
                OffsetDateTime now = fixture.databaseNow();
                CommandRow row = fixture.commandOf(id);
                Duration backoff = backoffs.get(cycle - 1);
                Duration remaining = Duration.between(now, row.availableAt());
                assertThat(remaining).as("backoff after deferral %s", cycle).isLessThanOrEqualTo(backoff);
                assertThat(remaining).as("backoff after deferral %s", cycle).isGreaterThan(backoff.minusSeconds(2));
                assertThat(row.attempts()).isZero();
                assertThat(row.claimToken()).isNull();
                if (cycle < 3) {
                    fixture.fastForward(row.id());
                }
            }

            // And once the start is older than the stuck age, the idle report lists it as stuck
            CommandRow last = fixture.commandOf(id);
            awaitCondition("the start to pass the stuck age", IDLE, () -> fixture.databaseNow()
                    .isAfter(last.recordedAt().plusSeconds(1)));
            IdleReport report = await(a.engine.executionProbe().awaitIdle(id, IDLE));
            a.stopExecutor();
            assertThat(report.stuck()).hasSize(1);
            assertThat(report.stuck().get(0).instanceId()).isEqualTo(id);
            assertThat(report.stuck().get(0).kind()).isEqualTo("START");

            // And exactly one deferral entry was written, naming the kind and the sequence and no payload
            List<String> deferrals = fixture.historyTextsOf(id).stream()
                    .filter(entry -> entry.startsWith("COMMAND_DEFERRED "))
                    .toList();
            assertThat(deferrals).hasSize(1);
            assertThat(deferrals.get(0)).contains("START").containsPattern("\\b" + last.seq() + "\\b");
            assertThat(deferrals.get(0)).doesNotContain(PAYLOAD_MARKER);

            // And the observer saw three deferral events with counts 1, 2 and 3
            List<CommandDeferred> events = recording.events().stream()
                    .filter(event -> event instanceof CommandDeferred)
                    .map(event -> (CommandDeferred) event)
                    .toList();
            assertThat(events).extracting(CommandDeferred::deferralCount).containsExactly(1, 2, 3);
            assertThat(events).extracting(CommandDeferred::kind).containsOnly("START");
            assertThat(fixture.commandOf(id).attempts()).isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a command on an ended instance is settled without loading a plan")
    void commandOnEndedInstanceIsSettledWithoutLoadingAPlan() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given engine B that runs an instance of the skeleton workflow, which then ends
            PgWorkflowDefinitionRegistryStore store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            DefinitionKey key = fixture.recordAvailable(store, ExecutorFixture.SKELETON);
            Node b = fixture.node("B").build();
            WorkflowInstanceId id = b.record(startOf("ended"));
            b.startExecutor();
            b.awaitIdle(id);
            b.stopExecutor();
            assertThat(fixture.instanceOf(id).status()).isEqualTo("WAITING");
            fixture.endInstanceThroughFixtureCommand(id);

            // And a signal left claimed with an expired lease, as a crashed executor leaves it
            long signalId = fixture.leaveClaimedSignalWithExpiredLease(id);

            // And the definition row retired, and engine A whose code lacks the workflow and whose registry counts
            // its plan lookups
            fixture.retireDefinitionRows(ExecutorFixture.SKELETON);
            LookupSpy spy = fixture.spyOn(fixture.registryWithout(ExecutorFixture.SKELETON));
            RecordingExecutorObserver recording = new RecordingExecutorObserver();
            Node a = fixture.node("A")
                    .withRegistry(spy.registry())
                    .pollIntervalMs(100)
                    .observers(recording)
                    .build();

            // When A's executor peeks and runs until idle
            a.startExecutor();
            awaitCondition("the signal to be settled", IDLE, () -> commandById(fixture, id, signalId)
                    .state()
                    .equals("REJECTED"));
            a.awaitIdle(id);
            a.stopExecutor();

            // Then the signal is rejected with its token cleared and an entry that names the ended instance
            CommandRow signal = commandById(fixture, id, signalId);
            assertThat(signal.state()).isEqualTo("REJECTED");
            assertThat(signal.claimToken()).isNull();
            List<String> rejections = fixture.historyTextsOf(id).stream()
                    .filter(entry -> entry.startsWith("COMMAND_REJECTED "))
                    .toList();
            assertThat(rejections).hasSize(1);
            assertThat(rejections.get(0)).containsAnyOf(ReasonCodes.INSTANCE_ENDED, "COMPLETED", "ended");

            // And it was never deferred, and the instance stays completed
            assertThat(signal.deferralCount()).isZero();
            assertThat(fixture.historyOf(id)).doesNotContain("COMMAND_DEFERRED");
            assertThat(recording.events()).noneMatch(WorkflowExecutorEvent.CommandDeferred.class::isInstance);
            assertThat(fixture.instanceOf(id).status()).isEqualTo("COMPLETED");

            // And no plan of the retired version was looked up
            assertThat(spy.lookupsOf(key)).isZero();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a stale or racing deferral writes nothing or once")
    void staleOrRacingDeferralWritesNothingOrOnce() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given an engine that can run the skeleton workflow and three repository clients, each over a pool of
            // its own
            Node b = fixture.node("B").build();
            List<PgWorkflowCommandRepository> clients = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                clients.add(new PgWorkflowCommandRepository(fixture.newPool(2), new PgDbExceptionMapper()));
            }

            // Given (a) a peeked head that engine B then claims and completes
            WorkflowInstanceId completed = b.record(startOf("completed-first"));
            CommandHead staleHead = headOf(await(clients.get(0).peek(10)), completed);
            b.startExecutor();
            b.awaitIdle(completed);
            b.stopExecutor();
            CommandRow settled = fixture.commandOf(completed);
            assertThat(settled.state()).isEqualTo("COMPLETE");
            List<String> historyBefore = fixture.historyOf(completed);

            // When A's deferral runs with its earlier observation
            int staleResult = await(clients.get(0).defer(staleHead));

            // Then it writes nothing
            assertThat(staleResult).isZero();
            assertThat(fixture.commandOf(completed)).isEqualTo(settled);
            assertThat(fixture.historyOf(completed)).isEqualTo(historyBefore);

            // Given (b) a fresh head
            WorkflowInstanceId raced = b.record(startOf("raced"));
            CommandHead racedHead = headOf(await(clients.get(0).peek(10)), raced);

            // When three deferrals of it run concurrently
            OffsetDateTime before = fixture.databaseNow();
            List<Future<Integer>> deferrals = new ArrayList<>();
            for (PgWorkflowCommandRepository client : clients) {
                deferrals.add(client.defer(racedHead));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> deferral : deferrals) {
                results.add(await(deferral));
            }
            OffsetDateTime after = fixture.databaseNow();

            // Then one wrote and the others wrote nothing: one count, one backoff and one history entry
            assertThat(results).containsExactlyInAnyOrder(0, 0, 1);
            CommandRow deferred = fixture.commandOf(raced);
            assertThat(deferred.state()).isEqualTo("DEFERRED");
            assertThat(deferred.deferralCount()).isEqualTo(1);
            assertThat(deferred.attempts()).isZero();
            assertThat(deferred.claimToken()).isNull();
            assertThat(deferred.availableAt()).isAfterOrEqualTo(before.plusSeconds(5));
            assertThat(deferred.availableAt()).isBeforeOrEqualTo(after.plusSeconds(5));
            assertThat(fixture.historyOf(raced).stream()
                            .filter("COMMAND_DEFERRED"::equals)
                            .count())
                    .isEqualTo(1);

            // And a later deferral of the same head, once the first has committed, writes nothing: the command is
            // already deferred into the future
            assertThat(await(clients.get(1).defer(racedHead))).isZero();
            assertThat(fixture.commandOf(raced)).isEqualTo(deferred);
            assertThat(fixture.historyOf(raced).stream()
                            .filter("COMMAND_DEFERRED"::equals)
                            .count())
                    .isEqualTo(1);

            // Given (c) another fresh head, and observations whose pin differs from the instance's pin
            WorkflowInstanceId repinned = b.record(startOf("repinned"));
            CommandHead pinnedHead = headOf(await(clients.get(0).peek(10)), repinned);
            CommandRow untouched = fixture.commandOf(repinned);
            List<String> repinnedHistory = fixture.historyOf(repinned);

            // When a deferral runs with an observed plan hash, and one with an observed version, that differ
            CommandHead otherHash = new CommandHead(
                    pinnedHead.commandId(),
                    pinnedHead.workflowId(),
                    pinnedHead.kind(),
                    pinnedHead.definitionScopeKey(),
                    pinnedHead.definitionId(),
                    pinnedHead.definitionVersion(),
                    "another-plan-hash",
                    false);
            CommandHead otherVersion = new CommandHead(
                    pinnedHead.commandId(),
                    pinnedHead.workflowId(),
                    pinnedHead.kind(),
                    pinnedHead.definitionScopeKey(),
                    pinnedHead.definitionId(),
                    pinnedHead.definitionVersion() + 1,
                    pinnedHead.planHash(),
                    false);
            int hashResult = await(clients.get(1).defer(otherHash));
            int versionResult = await(clients.get(2).defer(otherVersion));

            // Then both write nothing
            assertThat(hashResult).isZero();
            assertThat(versionResult).isZero();
            assertThat(fixture.commandOf(repinned)).isEqualTo(untouched);
            assertThat(fixture.historyOf(repinned)).isEqualTo(repinnedHistory);
        }
    }

    private static CommandHead headOf(List<CommandHead> heads, WorkflowInstanceId id) {
        return heads.stream()
                .filter(head -> head.workflowId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no head peeked for " + id + " in " + heads));
    }

    private static CommandRow commandById(ExecutorFixture fixture, WorkflowInstanceId id, long commandId) {
        return fixture.commandsOf(id).stream()
                .filter(command -> command.id() == commandId)
                .findFirst()
                .orElseThrow();
    }
}
