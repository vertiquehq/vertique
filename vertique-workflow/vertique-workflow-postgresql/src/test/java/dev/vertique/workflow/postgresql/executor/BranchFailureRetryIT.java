// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a branch failure inside a segment is handled by the segment: it rolls back everything the segment
 * did, including the work of the branches that ran before the failing one, and the command that ran the segment is
 * retried. No branch is left to a separate recovery.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BranchFailureRetryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_failure_retry_it")
            .withMigration("classpath:db/migration/workflow");

    /** The segment's history entries; none of them may exist after the failed attempt. */
    private static final List<String> SEGMENT_ENTRIES = List.of(
            "START",
            "FORK_DISPATCHED",
            "BRANCH_CREATED",
            "SIDE_EFFECT_RECORDED",
            "BRANCH_COMPLETED",
            "FAN_IN_COMPLETED",
            "COMMAND_COMPLETED");

    @Test
    @DisplayName("a transient branch failure rolls back the segment and the command retries")
    void transientBranchFailureRollsBackTheSegmentAndTheCommandRetries() {
        AtomicBoolean failSecondOnce = new AtomicBoolean(true);
        ForkFixture fork = ForkFixture.allOfTwo(failSecondOnce);
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), fork.definition(), List.of())) {
            await(f.fixture.observer().query(ForkFixture.REFUSE_RETRY_SCHEDULED).execute());
            try {
                // Given an all-branches fork whose second branch fails transiently once, after the first branch
                // wrote its intent, and a retry backoff long enough to look at the state in between
                Node node = f.node("engine")
                        .transientBackoffMs(3_000, 3_000)
                        .recorders(fork.recorder())
                        .build();
                WorkflowInstanceId id = await(
                        f.caller(() -> node.pool.withTransaction(tx -> node.engine.start(f.start("fork-1"), tx))));

                // When the start executes and fails once
                node.startExecutor();
                try {
                    awaitCondition(
                            "the first attempt to fail",
                            IDLE,
                            () -> f.fixture.commandOf(id).transientCount() >= 1);
                    CommandRow retrying = f.fixture.commandOf(id);

                    // Then the first branch's intent was written before the fault
                    assertThat(fork.intentWrites()).containsExactly("a-charge", "b-charge");

                    // And nothing of the segment remains: no branch token, join, intent, state or history entry
                    assertThat(f.scalar("SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1", id))
                            .isZero();
                    assertThat(f.scalar("SELECT count(*) FROM workflow_join_states WHERE workflow_id=$1", id))
                            .isZero();
                    assertThat(f.scalar("SELECT count(*) FROM signal_it_outbox WHERE workflow_id=$1", id))
                            .isZero();
                    assertThat(f.fixture.historyOf(id)).doesNotContainAnyElementsOf(SEGMENT_ENTRIES);
                    assertThat(f.fixture.instanceOf(id).status()).isEqualTo("PENDING");
                    assertThat(f.fixture.instanceOf(id).stateJson()).isNull();

                    // And the command alone carries the retry: pending again with its attempt restored
                    assertThat(retrying.state()).isEqualTo("PENDING");
                    assertThat(retrying.attempts()).isZero();
                    assertThat(retrying.transientCount()).isEqualTo(1);
                    assertThat(retrying.claimToken()).isNull();
                    awaitCondition(
                            "the backoff to elapse and the start to complete",
                            IDLE,
                            () -> f.fixture.commandOf(id).state().equals("COMPLETE"));
                    node.awaitIdle(id);
                } finally {
                    node.stopExecutor();
                }

                // Then the retried start completed with both branches joined once
                CommandRow command = f.fixture.commandOf(id);
                assertThat(command.state()).isEqualTo("COMPLETE");
                assertThat(command.attempts()).isEqualTo(1);
                assertThat(command.transientCount()).isEqualTo(1);
                assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
                assertThat(outboxSteps(f, id)).containsExactlyInAnyOrder("a-charge", "b-charge");
                assertThat(fork.intentWrites()).containsExactly("a-charge", "b-charge", "a-charge", "b-charge");
                List<String> history = f.fixture.historyOf(id);
                assertThat(Collections.frequency(history, "START")).isEqualTo(1);
                assertThat(Collections.frequency(history, "FAN_IN_COMPLETED")).isEqualTo(1);
                assertThat(Collections.frequency(history, "BRANCH_COMPLETED")).isEqualTo(2);
                assertThat(f.scalar(
                                "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1"
                                        + " AND status='COMPLETED'",
                                id))
                        .isEqualTo(2);
                assertThat(f.scalar(
                                "SELECT count(*) FROM workflow_join_states WHERE workflow_id=$1"
                                        + " AND status='COMPLETED'",
                                id))
                        .isEqualTo(1);

                // And no branch token was ever scheduled for a retry: a trigger would have failed the segment
                assertThat(f.scalar(
                                "SELECT count(*) FROM workflow_branch_tokens WHERE workflow_id=$1"
                                        + " AND status='RETRY_SCHEDULED'",
                                id))
                        .isZero();
            } finally {
                await(f.fixture
                        .observer()
                        .query(ForkFixture.ALLOW_RETRY_SCHEDULED)
                        .execute());
            }
        }
    }

    private static List<String> outboxSteps(SignalFixture f, WorkflowInstanceId id) {
        return await(f.fixture
                        .observer()
                        .preparedQuery("SELECT step_id FROM signal_it_outbox WHERE workflow_id=$1")
                        .execute(Tuple.of(id.value())))
                .stream()
                .map(row -> row.getString("step_id"))
                .toList();
    }

    static final class ForkFixture {

        static final String REFUSE_RETRY_SCHEDULED =
                "CREATE OR REPLACE FUNCTION branch_failure_retry_it_refuse() RETURNS trigger AS $$ BEGIN"
                        + " IF NEW.status = 'RETRY_SCHEDULED' THEN"
                        + " RAISE EXCEPTION 'a branch token was scheduled for a retry'; END IF; RETURN NEW; END;"
                        + " $$ LANGUAGE plpgsql;"
                        + " DROP TRIGGER IF EXISTS branch_failure_retry_it_refuse ON workflow_branch_tokens;"
                        + " CREATE TRIGGER branch_failure_retry_it_refuse BEFORE INSERT OR UPDATE"
                        + " ON workflow_branch_tokens FOR EACH ROW EXECUTE FUNCTION branch_failure_retry_it_refuse()";

        static final String ALLOW_RETRY_SCHEDULED =
                "DROP TRIGGER IF EXISTS branch_failure_retry_it_refuse ON workflow_branch_tokens;"
                        + " DROP FUNCTION IF EXISTS branch_failure_retry_it_refuse()";

        private final Definition definition;
        private final Recorder recorder;

        private ForkFixture(AtomicBoolean failSecondOnce) {
            this.recorder = new Recorder(failSecondOnce);
            this.definition = new Definition("branch-failure-retry", wf -> {
                wf.initialStep("fork")
                        .fork("fork")
                        .branch("a", "a-charge", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "b-charge", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join");
                wf.dispatch("a-charge", "charge.service", state -> Map.of("ref", state.ref()), "a-done");
                wf.complete("a-done");
                wf.dispatch("b-charge", "charge.service", state -> Map.of("ref", state.ref()), "b-done");
                wf.complete("b-done");
                wf.join("join")
                        .allRequired((state, results) -> state)
                        .toStep("done")
                        .onFailure("failed")
                        .endJoin();
                wf.complete("done");
                wf.complete("failed");
            });
        }

        /** Two branches; the second branch's call fails transiently, after it wrote, while the flag is set. */
        static ForkFixture allOfTwo(AtomicBoolean failSecondOnce) {
            return new ForkFixture(failSecondOnce);
        }

        Definition definition() {
            return definition;
        }

        WorkflowSideEffectRecorder<SqlClient> recorder() {
            return recorder;
        }

        /** The steps of every service call the recorder saw, rolled back or not, in order. */
        List<String> intentWrites() {
            return List.copyOf(recorder.writes);
        }

        private static final class Recorder implements WorkflowSideEffectRecorder<SqlClient> {
            private final AtomicBoolean failSecondOnce;
            private final List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<>();

            private Recorder(AtomicBoolean failSecondOnce) {
                this.failSecondOnce = failSecondOnce;
            }

            @Override
            public IntentKind kind() {
                return IntentKind.SERVICE;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                String step = intent.correlation().stepId();
                return tx.preparedQuery("INSERT INTO signal_it_outbox(workflow_id, step_id) VALUES ($1,$2)")
                        .execute(Tuple.of(intent.correlation().workflowId().value(), step))
                        .compose(rows -> {
                            writes.add(step);
                            if (step.equals("b-charge") && failSecondOnce.compareAndSet(true, false)) {
                                return Future.<RecorderResult>failedFuture(
                                        FailingTransactions.connectionFailure().get());
                            }
                            return Future.succeededFuture(RecorderResult.empty());
                        });
            }
        }
    }
}
