// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplyWaitDeliveryIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reply_wait_delivery_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void dispatchWritesTheRecordAndTheRowInOneTransaction() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            AtomicReference<long[]> observed = new AtomicReference<>();
            var node = f.node()
                    .leaseSeconds(5)
                    .commands(ExecutorFixture.intercepting("complete", (call, method, args, real) -> {
                        if (call != 1) return real.call();
                        return f.fixture
                                .observer()
                                .query("SELECT (SELECT count(*) FROM workflow_signal_waits) AS waits, "
                                        + "(SELECT count(*) FROM reply_test_intent) AS intents")
                                .execute()
                                .map(rows -> {
                                    Row row = rows.iterator().next();
                                    observed.set(new long[] {row.getLong("waits"), row.getLong("intents")});
                                    return 0;
                                });
                    }))
                    .build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(observed.get()).containsExactly(0L, 0L);
            assertThat(f.waitRecords(id)).hasSize(1);
            var wait = f.waitRecords(id).get(0);
            assertThat(wait.stepId()).isEqualTo("reserve.reply");
            assertThat(wait.waitId().version()).isEqualTo(4);
            assertThat(wait.correlationKey()).isEqualTo(wait.waitId().toString());
            assertThat(wait.contractName()).isEqualTo(f.contractName);
            assertThat(wait.signalName()).isEqualTo(ReplyStepFixture.REPLY);
            assertThat(f.intents(id)).containsExactly(wait.waitId());
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("WAITING");
        }
    }

    @Test
    void signalWaitResumesTheInstanceAndARepeatIsADuplicate() throws Exception {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            var other = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.awaitIdle(other);
            node.stopExecutor();
            UUID waitId = f.waitRecords(id).get(0).waitId();
            var value = new ReplyStepFixture.Reserved("reserved-7");
            assertThat(f.signalWait(node, id, waitId, value).outcome()).isEqualTo(Outcome.RECORDED);
            assertThat(f.signalWait(node, id, waitId, value).outcome()).isEqualTo(Outcome.DUPLICATE);
            var rows = await(f.fixture
                    .observer()
                    .preparedQuery(
                            "SELECT target_json, dedup_key, provenance_json, actor_kind, actor_reason, attribution_status "
                                    + "FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                    .execute(Tuple.of(id.value())));
            assertThat(rows.size()).isEqualTo(1);
            Row command = rows.iterator().next();
            JsonObject target = (JsonObject) command.getValue("target_json");
            assertThat(target.getString("name")).isEqualTo(ReplyStepFixture.REPLY);
            assertThat(target.getString("waitId")).isEqualTo(waitId.toString());
            String digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(waitId.toString().getBytes(StandardCharsets.UTF_8)));
            assertThat(command.getString("dedup_key")).isEqualTo(digest);
            JsonObject provenance = (JsonObject) command.getValue("provenance_json");
            assertThat(provenance.getString("transport")).isEqualTo("outbox");
            assertThat(provenance.getString("source")).isEqualTo("reply");
            assertThat(provenance.getJsonObject("attributes").getMap().values()).contains("inventory.reserve", "7");
            assertThat(command.getString("actor_kind")).isEqualTo("SYSTEM");
            assertThat(command.getString("actor_reason")).isEqualTo("outbox reply");
            assertThat(command.getString("attribution_status")).isEqualTo("VERIFIED");
            var dedup = await(f.fixture
                    .observer()
                    .preparedQuery("SELECT kind, scope_key, dedup_namespace, key FROM workflow_dedup "
                            + "WHERE workflow_id = $1 AND kind IN ('signal', 'correlated')")
                    .execute(Tuple.of(id.value())));
            assertThat(dedup.size()).isEqualTo(1);
            Row claim = dedup.iterator().next();
            assertThat(claim.getString("kind")).isEqualTo("correlated");
            assertThat(claim.getString("scope_key")).isEqualTo("default");
            assertThat(claim.getString("dedup_namespace")).isNotBlank();
            assertThat(claim.getString("key")).isEqualTo(digest);
            UUID otherWait = f.waitRecords(other).get(0).waitId();
            assertThat(f.signalWait(node, other, otherWait, value).outcome()).isEqualTo(Outcome.RECORDED);
            Row otherClaim = await(f.fixture
                            .observer()
                            .preparedQuery("SELECT dedup_namespace FROM workflow_dedup "
                                    + "WHERE workflow_id = $1 AND kind = 'correlated'")
                            .execute(Tuple.of(other.value())))
                    .iterator()
                    .next();
            assertThat(otherClaim.getString("dedup_namespace")).isEqualTo(claim.getString("dedup_namespace"));
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitRecords(id)).isEmpty();
            var instance = f.fixture.instanceOf(id);
            assertThat(instance.currentStepId()).isEqualTo("after");
            assertThat(String.valueOf(instance.stateJson())).contains("reserved-7");
        }
    }

    @Test
    void replyAtTheCapIsRecordedAndTheCountReturns() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            AtomicBoolean admitting = new AtomicBoolean(true);
            var node = f.node().intakeAdmission(admitting::get).build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            UUID waitId = f.waitRecords(id).get(0).waitId();
            f.fillOpenSignals(node, id);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            long before = f.openSignals(id);
            assertThat(before).isEqualTo(100);
            admitting.set(false);
            assertThatThrownBy(() ->
                            await(node.pool.withTransaction(tx -> node.engine.signal(id, "other", "x", "refused", tx))))
                    .hasRootCauseInstanceOf(WorkflowIntakeRefusedException.class);
            assertThat(f.signalWait(node, id, waitId, new ReplyStepFixture.Reserved("ok"))
                            .outcome())
                    .isEqualTo(Outcome.RECORDED);
            assertThat(f.openSignals(id)).isEqualTo(before + 1);
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.openSignals(id)).isEqualTo(before);
            assertThat(f.fixture.instanceOf(id).currentStepId()).isEqualTo("after");
        }
    }

    @Test
    void waitOpenIsTrueOnlyForAnOpenRecordOfThatInstance() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            var other = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.awaitIdle(other);
            node.stopExecutor();
            UUID waitId = f.waitRecords(id).get(0).waitId();
            assertThat(f.waitOpen(node, id, waitId)).isTrue();
            assertThat(f.waitOpen(node, other, waitId)).isFalse();
            f.signalWait(node, id, waitId, new ReplyStepFixture.Reserved("ok"));
            node.startExecutor();
            node.awaitIdle(id);
            assertThat(f.waitOpen(node, id, waitId)).isFalse();
        }
    }

    @Test
    void replyWithoutProvenanceFailsWithoutRecording() {
        try (ReplyStepFixture f = ReplyStepFixture.open(db.toPoolConfig())) {
            var node = f.node().build();
            var id = f.startCall(node);
            node.startExecutor();
            node.awaitIdle(id);
            node.stopExecutor();
            UUID waitId = f.waitRecords(id).get(0).waitId();
            assertThatThrownBy(() -> await(node.pool.withTransaction(
                            tx -> dev.vertique.workflow.engine.ReplySignalsTestBridge.relayedSignals(node.engine)
                                    .signalWait(id, waitId, new ReplyStepFixture.Reserved("ok"), null, tx))))
                    .hasRootCauseInstanceOf(NullPointerException.class);
            var rows = await(f.fixture
                    .observer()
                    .preparedQuery(
                            "SELECT count(*) AS n FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                    .execute(Tuple.of(id.value())));
            assertThat(rows.iterator().next().getLong("n")).isZero();
        }
    }
}
