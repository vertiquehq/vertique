// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CorrelationCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.IdentitySnapshotCtx;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that applying a recorded signal never binds an identity snapshot from the instance's stored carrier. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordedSignalIdentityIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_signal_identity_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("an instance carrier that still holds an identity snapshot binds its context but never the identity")
    void storedIdentitySnapshotIsNeverBoundWhenASignalIsApplied() {
        try (SignalFixture f = new SignalFixture(
                db.toPoolConfig(), new RecordedSignalIT.OrderSignalFixture(), List.of("paid", "shipped", "note"))) {
            List<Optional<String>> identities = new CopyOnWriteArrayList<>();
            List<Optional<String>> correlations = new CopyOnWriteArrayList<>();
            WorkflowSideEffectRecorder<SqlClient> spy = new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    identities.add(f.fixture
                            .holder()
                            .current(IdentitySnapshotCtx.class)
                            .map(IdentitySnapshotCtx::subject));
                    correlations.add(
                            f.fixture.holder().current(CorrelationCtx.class).map(CorrelationCtx::correlationId));
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
            Node node = f.node("engine").carryContext().recorders(spy).build();
            // Given an instance, started before identity snapshots were dropped, whose carrier holds one
            WorkflowInstanceId id = f.waiting(node, "identity");
            JsonObject carrier = DurableMetadata.of("correlation", new JsonObject().put("correlationId", "C"))
                    .with("identity-snapshot", new JsonObject().put("subject", "user-77"))
                    .toCarrier();
            await(node.pool
                    .preparedQuery("UPDATE workflow_instances SET metadata = $1 WHERE id = $2")
                    .execute(Tuple.of(carrier, id.value())));

            // When a signal is recorded and applied, which dispatches an intent inside the bound drive
            f.record(node, id, "paid", signal("paid"), "paid");
            f.idle(node, id);

            // Then the rest of the carrier was bound, and no identity could be rebuilt from it
            assertThat(f.signal(id, "paid").state()).isEqualTo("COMPLETE");
            assertThat(correlations).containsExactly(Optional.of("C"));
            assertThat(identities).containsExactly(Optional.empty());
        }
    }
}
