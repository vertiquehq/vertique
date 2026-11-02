// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.OutboxReplies.Completion;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OutboxRepliesIT {
    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_outbox_replies_it");

    @BeforeAll
    static void migrate() {
        for (String[] migration : new String[][] {
            {"workflow", "flyway_workflow_history"},
            {"inbox-outbox", "flyway_inbox_outbox_history"},
            {"job", "flyway_job_history"}
        }) {
            var f = Flyway.configure()
                    .dataSource(db.jdbcUrl(), db.username(), db.password())
                    .locations("classpath:db/migration/" + migration[0])
                    .table(migration[1]);
            if (!migration[0].equals("workflow")) f.baselineOnMigrate(true).baselineVersion("0");
            f.load().migrate();
        }
    }

    @Test
    void acceptedRowSettlesAndAStoredHandleCompletesAfterARestart(Vertx vertx) {
        String handle;
        WorkflowInstanceId id;
        try (ReplyGraph first = new ReplyGraph(vertx, db)) {
            id = first.startLater("restart");
            handle = first.storedHandle("restart", 0);
            published(first, id);
            assertThat(first.rows("SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", id.value()))
                    .hasSize(1);
            assertThat(first.graph.replyProbe().outcomes).isEmpty();
            assertThat(first.signals(id)).isEmpty();
        }
        try (ReplyGraph second = new ReplyGraph(vertx, db)) {
            assertThat(complete(second, handle, new ReplyGraph.Reserved("after-restart"), null, true))
                    .isEqualTo(Completion.RECORDED);
            done(second, id, "after-restart");
            var actual = ReplyGraph.json(second.signals(id).getFirst(), "provenance_json");
            var expected = dev.vertique.workflow.ops.SignalProvenance.completed();
            assertThat(actual.getString("transport")).isEqualTo(expected.transport());
            assertThat(actual.getString("source")).isEqualTo(expected.source());
            assertThat(actual.getJsonObject("attributes").getMap()).isEqualTo(expected.attributes());
        }
    }

    @Test
    void oneShotIterableIsRecordedWithoutLosingItsValues(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId id = run.startLater("one-shot");
            String handle = run.storedHandle("one-shot", 0);
            published(run, id);
            run.pauseExecutor();
            try {
                java.util.Iterator<Integer> iterator = java.util.List.of(42).iterator();
                Iterable<Integer> value = () -> iterator;
                assertThat(complete(run, handle, value, null, false)).isEqualTo(Completion.RECORDED);
                var rows = run.rows(
                        "SELECT payload_json = '[42]'::jsonb AS matches FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'",
                        id.value());
                assertThat(rows).singleElement().satisfies(row -> assertThat(row.getBoolean("matches"))
                        .isTrue());
            } finally {
                run.resumeExecutor();
            }
        }
    }

    @Test
    void completionRecordsItsReasonAndItsIdentity(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId reported = run.startLater("reported");
            String rh = run.storedHandle("reported", 0);
            published(run, reported);
            assertThat(ReplyGraph.await(run.graph.replies().fail(rh))).isEqualTo(Completion.RECORDED);
            done(run, reported, "REPORTED:0");
            WorkflowInstanceId oversized = run.startLater("oversized");
            String oh = run.storedHandle("oversized", 0);
            published(run, oversized);
            assertThat(complete(run, oh, new ReplyGraph.Reserved("x".repeat(300_000)), null, false))
                    .isEqualTo(Completion.STALE);
            done(run, oversized, "REPLY_REFUSED:0");
            SecurityContext user = SecurityContexts.assemble(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "caller-1", Map.of())),
                    new dev.vertique.security.AuthenticationState(
                            dev.vertique.security.DefaultAuthMethod.jwt(),
                            java.util.List.of(),
                            java.util.Optional.empty(),
                            java.util.Optional.empty(),
                            Map.of()),
                    dev.vertique.security.authz.AuthorizationClaims.empty(),
                    java.util.Optional.empty());
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            for (String label : new String[] {"root", "absent", "anonymous", "user", "degraded"}) {
                WorkflowInstanceId id = run.startLater(label);
                String handle = run.storedHandle(label, 0);
                published(run, id);
                SecurityContext security = (label.equals("user") || label.equals("degraded"))
                        ? user
                        : label.equals("anonymous") ? anonymous : null;
                assertThat(complete(
                                run,
                                handle,
                                new ReplyGraph.Reserved(label),
                                security,
                                label.equals("root"),
                                label.equals("degraded")))
                        .isEqualTo(Completion.RECORDED);
                done(run, id, label);
                var command = run.signals(id).getFirst();
                assertThat(command.getString("actor_kind"))
                        .isEqualTo((label.equals("user") || label.equals("degraded")) ? "USER" : "SYSTEM");
                assertThat(command.getString("attribution_status"))
                        .isEqualTo(label.equals("degraded") ? "DEGRADED" : "VERIFIED");
                if (label.equals("user") || label.equals("degraded"))
                    assertThat(command.getString("actor_id")).isEqualTo("caller-1");
                else {
                    assertThat(command.getString("actor_id")).isNull();
                    assertThat(command.getString("actor_reason")).isEqualTo("outbox reply");
                }
            }
        }
    }

    @Test
    void unencodableIterableIsRefusedWithoutExposingItsException(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId id = run.startLater("bad-iterable");
            String handle = run.storedHandle("bad-iterable", 0);
            published(run, id);
            Iterable<Object> bad = () -> {
                throw new IllegalStateException("SECRET-ITERABLE");
            };
            assertThat(complete(run, handle, bad, null, false)).isEqualTo(Completion.STALE);
            done(run, id, "REPLY_REFUSED:0");
            assertThat(run.signals(id)).hasSize(1);
            assertThat(String.valueOf(run.signals(id).getFirst().getValue("payload_json")))
                    .doesNotContain("SECRET-ITERABLE");
            assertThat(run.rows(
                            "SELECT payload_json::text AS payload_json FROM workflow_history WHERE workflow_id = $1",
                            id.value()))
                    .extracting(row -> row.getString("payload_json"))
                    .noneMatch(payload -> payload != null && payload.contains("SECRET-ITERABLE"));
            assertThat(run.graph.replyProbe().outcomes)
                    .contains(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.REFUSED);
        }
    }

    @Test
    void completionAnswersFollowTheFence(Vertx vertx) {
        dev.vertique.workflow.ops.RelayedSignals<Object> defaults = new dev.vertique.workflow.ops.RelayedSignals<>() {
            public Future<Void> signal(
                    WorkflowInstanceId id,
                    String name,
                    Object value,
                    String key,
                    String fork,
                    String branch,
                    Object tx) {
                return Future.succeededFuture();
            }

            public Future<Void> signal(
                    WorkflowInstanceId id,
                    String name,
                    Object value,
                    String key,
                    String fork,
                    String branch,
                    dev.vertique.core.context.DurableMetadata carrier,
                    Object tx) {
                return Future.succeededFuture();
            }
        };
        assertThat(defaults.recordedOutcomeName(
                                new WorkflowInstanceId(java.util.UUID.randomUUID()),
                                java.util.UUID.randomUUID(),
                                new Object())
                        .cause())
                .isInstanceOf(UnsupportedOperationException.class);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId timed = run.startLater("timed");
            String th = run.storedHandle("timed", 0);
            published(run, timed);
            ReplyGraph.await(run.pool.withTransaction(tx -> tx.preparedQuery(
                            "UPDATE workflow_timers SET fire_at = now() - interval '1 second' WHERE workflow_id = $1")
                    .execute(Tuple.of(timed.value()))
                    .compose(v -> tx.preparedQuery(
                                    "UPDATE workflow_commands SET fire_at = now() - interval '1 second', available_at = now() - interval '1 second' WHERE workflow_id = $1 AND kind = 'TIMER'")
                            .execute(Tuple.of(timed.value())))));
            done(run, timed, "timeout");
            assertThat(complete(run, th, new ReplyGraph.Reserved("late"), null, false))
                    .isEqualTo(Completion.STALE);
            assertThat(run.graph.replyProbe().outcomes)
                    .containsExactly(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE);
            assertThat(run.signals(timed)).isEmpty();

            WorkflowInstanceId success = run.startLater("fence-success");
            String sh = run.storedHandle("fence-success", 0);
            published(run, success);
            run.pauseExecutor();
            try {
                assertThat(complete(run, sh, new ReplyGraph.Reserved("first"), null, false))
                        .isEqualTo(Completion.RECORDED);
                assertThat(complete(run, sh, new ReplyGraph.Reserved("again"), null, false))
                        .isEqualTo(Completion.DUPLICATE);
                assertThat(ReplyGraph.await(run.graph.replies().fail(sh))).isEqualTo(Completion.STALE);
                var waitId = run.rows(
                                "SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", success.value())
                        .getFirst()
                        .getUUID("wait_id");
                var duplicate = ReplyGraph.await(run.pool.withTransaction(tx -> run.graph
                        .relayedSignals()
                        .signalWait(
                                success,
                                waitId,
                                new ReplyGraph.Reserved("x".repeat(300_000)),
                                dev.vertique.workflow.ops.SignalProvenance.completed(),
                                tx)));
                assertThat(duplicate.outcome())
                        .isEqualTo(dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome.DUPLICATE);
                assertThat(complete(run, sh, new ReplyGraph.Reserved("x".repeat(300_000)), null, false))
                        .isEqualTo(Completion.STALE);
                assertThat(run.graph.replyProbe().outcomes)
                        .containsExactly(
                                dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE,
                                dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.RECORDED,
                                dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.DUPLICATE,
                                dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE,
                                dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.REFUSED);
                assertThat(run.signals(success)).hasSize(1);
            } finally {
                run.resumeExecutor();
            }
            done(run, success, "first");
            assertThat(complete(run, sh, new ReplyGraph.Reserved("again"), null, false))
                    .isEqualTo(Completion.DUPLICATE);
            WorkflowInstanceId failure = run.startLater("fence-failure");
            String fh = run.storedHandle("fence-failure", 0);
            published(run, failure);
            run.pauseExecutor();
            try {
                assertThat(ReplyGraph.await(run.graph.replies().fail(fh))).isEqualTo(Completion.RECORDED);
                assertThat(ReplyGraph.await(run.graph.replies().fail(fh))).isEqualTo(Completion.DUPLICATE);
                assertThat(complete(run, fh, new ReplyGraph.Reserved("wrong"), null, false))
                        .isEqualTo(Completion.STALE);
            } finally {
                run.resumeExecutor();
            }
            done(run, failure, "REPORTED:0");
            WorkflowInstanceId early = run.startLater("early");
            published(run, early);
            done(run, early, "early");
            assertThat(run.signals(early)).hasSize(1);
            String bad = "not-a-reply-handle";
            Future<Completion> malformed = run.graph.replies().complete(bad, null);
            assertThat(malformed.failed()).isTrue();
            assertThat(malformed.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(malformed.cause().getMessage()).doesNotContain(bad);
        }
    }

    @Test
    void migrationMakesTheOldHandleStaleAndTheNewOneResumes(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId id = run.startLater("migration");
            String old = run.storedHandle("migration", 0);
            published(run, id);
            ReplyGraph.await(run.pool.withTransaction(
                    tx -> run.graph.transactionalOperations().migrate(id, 2L, tx)));
            String next = run.storedHandle("migration", 1);
            assertThat(next).isNotEqualTo(old);
            assertThat(complete(run, old, new ReplyGraph.Reserved("old"), null, false))
                    .isEqualTo(Completion.STALE);
            assertThat(complete(run, next, new ReplyGraph.Reserved("new"), null, false))
                    .isEqualTo(Completion.RECORDED);
            done(run, id, "new");
        }
    }

    private static void published(ReplyGraph run, WorkflowInstanceId id) {
        run.until(
                "accepted row to publish",
                () -> run.row(id) != null && "PUBLISHED".equals(run.row(id).getString("state")));
    }

    private static void done(ReplyGraph run, WorkflowInstanceId id, String expected) {
        run.until("completion", () -> "COMPLETED".equals(run.instance(id).getString("status")));
        assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                .isEqualTo(expected);
    }

    private static Completion complete(
            ReplyGraph run, String handle, Object value, SecurityContext security, boolean root) {
        return complete(run, handle, value, security, root, false);
    }

    private static Completion complete(
            ReplyGraph run, String handle, Object value, SecurityContext security, boolean root, boolean degraded) {
        Promise<Completion> result = Promise.promise();
        ContextInternal base = (ContextInternal) run.vertx.getOrCreateContext();
        ContextInternal caller = root ? base.unwrap() : base.unwrap().duplicate();
        caller.runOnContext(ignored -> {
            var binding = security == null ? null : run.graph.contextHolder().bind(SecurityContext.class, security);
            var degradation = degraded
                    ? run.graph
                            .contextHolder()
                            .bind(
                                    dev.vertique.security.SnapshotDegradationMarker.class,
                                    new dev.vertique.security.SnapshotDegradationMarker(
                                            "TAMPERED", java.util.Optional.empty()))
                    : null;
            run.graph.replies().complete(handle, value).onComplete(done -> {
                try {
                    assertThat(Vertx.currentContext()).isSameAs(caller);
                    if (degradation != null) degradation.close();
                    if (binding != null) binding.close();
                    result.handle(done);
                } catch (Throwable failure) {
                    result.tryFail(failure);
                }
            });
        });
        return ReplyGraph.await(result.future());
    }
}
