// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxOutcome;
import dev.vertique.inboxoutbox.OutboxOutcomeConsumer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ServiceReplyIT {
    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_service_reply_it");

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    static void migrate(String path, String table, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(path)
                .table(table);
        if (baseline) flyway.baselineOnMigrate(true).baselineVersion("0");
        flyway.load().migrate();
    }

    @Test
    void successResumesTheInstanceAsTheSystemIdentity(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            CorrelationContext correlation = CorrelationContext.generated("reply-proof");
            WorkflowInstanceId id = run.startCorrelated("success", correlation);
            run.until("the reply to complete the instance", () -> "COMPLETED"
                    .equals(run.instance(id).getString("status")));
            run.until(
                    "the row to publish",
                    () -> run.row(id) != null && "PUBLISHED".equals(run.row(id).getString("state")));
            assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                    .isEqualTo("reserved-success");
            assertThat(run.graph.inventoryProbe().calls).hasValue(1);
            assertThat(run.signals(id)).singleElement().satisfies(signal -> {
                assertThat(signal.getString("actor_kind")).isEqualTo("SYSTEM");
                assertThat(signal.getString("actor_reason")).isEqualTo("outbox reply");
                var provenance = ReplyGraph.json(signal, "provenance_json");
                assertThat(provenance.getString("transport")).isEqualTo("outbox");
                assertThat(provenance.getString("source")).isEqualTo("reply");
                assertThat(provenance.getJsonObject("attributes").getString("target"))
                        .isEqualTo("inventory.reserve");
                assertThat(provenance.getJsonObject("attributes").getString("entryId"))
                        .isEqualTo(Long.toString(run.row(id).getLong("id")));
                assertThat(ReplyGraph.json(run.row(id), "metadata").encode())
                        .contains(correlation.correlationId().value());
                assertThat(ReplyGraph.json(signal, "context_json").encode())
                        .contains(correlation.correlationId().value());
            });
            WorkflowInstanceId noValue = run.start("service-reply-void", "void");
            run.until("the void reply", () -> "COMPLETED"
                    .equals(run.instance(noValue).getString("status")));
            run.until(
                    "the void row to publish",
                    () -> run.row(noValue) != null
                            && "PUBLISHED".equals(run.row(noValue).getString("state")));
            assertThat(ReplyGraph.json(run.instance(noValue), "state_json").getString("result"))
                    .isEqualTo("acknowledged");
            assertThat(run.graph.inventoryProbe().ackCalls).hasValue(1);
        }
    }

    @Test
    void siblingCallProceedsWhileOtherBranchRetries(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId fork = run.start("service-reply-fork", "fork");
            run.until("the healthy branch to complete while its sibling retries", () -> !run.rows(
                            "SELECT id FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id = 'b' AND status = 'COMPLETED'",
                            fork.value())
                    .isEmpty());
            var failed = run.rows(
                            "SELECT * FROM outbox WHERE headers->>'x-workflow-id' = $1 AND payload->>'reference' = 'bad'",
                            fork.value().toString())
                    .getFirst();
            var healthy = run.rows(
                            "SELECT * FROM outbox WHERE headers->>'x-workflow-id' = $1 AND payload->>'reference' = 'good'",
                            fork.value().toString())
                    .getFirst();
            assertThat(failed.getLong("id")).isLessThan(healthy.getLong("id"));
            assertThat(failed.getString("aggregate_id")).isEqualTo(fork.value().toString());
            assertThat(healthy.getString("aggregate_id")).isEqualTo(fork.value().toString());
            assertThat(failed.getInteger("attempt")).isLessThan(failed.getInteger("max_attempts"));
            assertThat(healthy.getString("state")).isEqualTo("PUBLISHED");
            assertThat(run.signals(fork)).hasSize(1);

            // Close the retrying wait and let the relay settle its row before the next graph starts.
            ReplyGraph.await(run.pool.withTransaction(
                    tx -> run.graph.transactionalOperations().cancel(fork, "test cleanup", tx)));
            run.until("the fork's outbox rows to settle", () -> run.rows(
                            "SELECT id FROM outbox WHERE headers->>'x-workflow-id' = $1 AND state IN ('PENDING', 'PROCESSING')",
                            fork.value().toString())
                    .isEmpty());
        }
    }

    @Test
    void failedForkWaitsForForwardDeliveryBeforeCompensation(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db, 60_000)) {
            run.graph.inventoryProbe().heldGood = Promise.promise();
            WorkflowInstanceId id = run.start("service-reply-failed-fork", "fork-barrier");
            run.until(
                    "good forward in flight",
                    () -> run.graph
                                    .inventoryProbe()
                                    .perReference
                                    .getOrDefault("good", new java.util.concurrent.atomic.AtomicInteger())
                                    .get()
                            > 0);
            run.until("compensation recorded after failed join", () -> !run.rows(
                            "SELECT id FROM outbox WHERE headers->>'x-workflow-id' = $1 AND destination = 'inventory.ack'",
                            id.value().toString())
                    .isEmpty());
            assertThat(run.rows(
                                    "SELECT status FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id = 'a'",
                                    id.value())
                            .getFirst()
                            .getString("status"))
                    .isEqualTo("FAILED");
            assertThat(run.rows("SELECT status FROM workflow_join_states WHERE workflow_id = $1", id.value())
                            .getFirst()
                            .getString("status"))
                    .isEqualTo("FAILED");
            var forward = run.rows(
                            "SELECT state FROM outbox WHERE headers->>'x-workflow-id' = $1 AND payload->>'reference' = 'good'",
                            id.value().toString())
                    .getFirst();
            assertThat(forward.getString("state")).isEqualTo("PROCESSING");
            assertThat(run.graph.inventoryProbe().ackCalls).hasValue(0);
            run.graph.inventoryProbe().heldGood.complete(new ReplyGraph.Reserved("ok"));
            run.until("forward terminal", () -> "PUBLISHED"
                    .equals(run.rows(
                                    "SELECT state FROM outbox WHERE headers->>'x-workflow-id' = $1 AND payload->>'reference' = 'good'",
                                    id.value().toString())
                            .getFirst()
                            .getString("state")));
            run.until(
                    "undo delivered", () -> run.graph.inventoryProbe().ackCalls.get() == 1);
        }
    }

    @Test
    void waitCheckReturnsToCallerWithBindingsReleasedBeforeDelivery(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.graph.inventoryProbe().held = Promise.promise();
            WorkflowInstanceId id = run.startCall("context");
            run.until("the call row", () -> run.row(id) != null);
            var row = run.row(id);
            String handle = ReplyGraph.json(row, "headers").getString(OutboxOutcomeConsumer.HANDLE_HEADER);
            OutboxOutcomeConsumer consumer = run.graph.outcomeConsumer().orElseThrow();
            CorrelationContext correlation = CorrelationContext.generated("caller");
            ContextInternal caller = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            Promise<Void> done = Promise.promise();
            caller.runOnContext(ignored -> {
                ContextHolder.Scope bound = run.graph.contextHolder().bind(CorrelationContext.class, correlation);
                consumer.awaits(handle)
                        .compose(open -> {
                            assertThat(open).isTrue();
                            assertThat(Vertx.currentContext()).isSameAs(caller);
                            assertThat(run.graph
                                            .contextHolder()
                                            .current(CorrelationContext.class)
                                            .orElseThrow())
                                    .isSameAs(correlation);
                            return consumer.onOutcome(new OutboxOutcome.Success(
                                    row.getLong("id"),
                                    "inventory.reserve",
                                    handle,
                                    Map.of(CorrelationContext.class.getName(), correlation),
                                    1,
                                    new ReplyGraph.Reserved("recorded")));
                        })
                        .onComplete(result -> {
                            try {
                                assertThat(Vertx.currentContext()).isSameAs(caller);
                                assertThat(run.graph
                                                .contextHolder()
                                                .current(CorrelationContext.class)
                                                .orElseThrow())
                                        .isSameAs(correlation);
                                if (result.failed()) done.fail(result.cause());
                                else done.complete();
                            } catch (Throwable failure) {
                                done.tryFail(failure);
                            } finally {
                                bound.close();
                            }
                        });
            });
            ReplyGraph.await(done.future());
            run.graph.inventoryProbe().held.tryComplete(new ReplyGraph.Reserved("late"));
            run.until("the delivered reply", () -> "COMPLETED"
                    .equals(run.instance(id).getString("status")));
            assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                    .isEqualTo("recorded");
        }
    }

    @Test
    void crashBetweenRecordingAndSettlingAppliesOneSignal(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            ReplyGraph.await(run.pool.query("CREATE SEQUENCE refuse_settle_seq").execute());
            ReplyGraph.await(run.pool.query("""
                CREATE FUNCTION refuse_first_settle() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.state = 'PROCESSING' AND NEW.state = 'PUBLISHED'
                            AND nextval('refuse_settle_seq') = 1 THEN
                        RAISE EXCEPTION 'settle interrupted';
                    END IF;
                    RETURN NEW;
                END $$
                """).execute());
            ReplyGraph.await(run.pool.query("""
                CREATE TRIGGER refuse_first_settle BEFORE UPDATE ON outbox
                FOR EACH ROW EXECUTE FUNCTION refuse_first_settle()
                """).execute());
            try {
                run.graph.inventoryProbe().held = Promise.promise();
                WorkflowInstanceId id = run.startCall("settle-crash");
                run.until(
                        "the first service call",
                        () -> run.graph.inventoryProbe().calls.get() == 1);
                run.pauseExecutor();
                run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("reserved-settle-crash"));
                run.until(
                        "a recorded reply with an unsettled lease",
                        () -> run.row(id) != null
                                && "PROCESSING".equals(run.row(id).getString("state"))
                                && !run.signals(id).isEmpty());
                run.until(
                        "the relay lease to expire",
                        () -> !run.rows("""
                        SELECT id FROM outbox WHERE id = $1 AND claimed_at < now() - interval '1 second'
                        """, run.row(id).getLong("id")).isEmpty());
                ReplyGraph.await(run.graph.outboxRepository().reclaimStale(Duration.ofSeconds(1)));
                run.until("the reclaimed row to publish", () -> "PUBLISHED"
                        .equals(run.row(id).getString("state")));
                assertThat(run.graph.inventoryProbe().calls).hasValue(2);
                assertThat(run.signals(id)).hasSize(1);
                assertThat(run.graph.replyProbe().outcomes)
                        .contains(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.DUPLICATE);
                run.resumeExecutor();
                run.until("the recorded reply to apply", () -> "COMPLETED"
                        .equals(run.instance(id).getString("status")));
                assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                        .isEqualTo("reserved-settle-crash");
                var settledRow = run.row(id);
                long duplicates = run.graph.replyProbe().outcomes.stream()
                        .filter(outcome -> outcome == dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.DUPLICATE)
                        .count();
                String handle = ReplyGraph.json(settledRow, "headers").getString(OutboxOutcomeConsumer.HANDLE_HEADER);
                ReplyGraph.await(run.graph
                        .outcomeConsumer()
                        .orElseThrow()
                        .onOutcome(new OutboxOutcome.Success(
                                settledRow.getLong("id"),
                                settledRow.getString("destination"),
                                handle,
                                run.dispatchContext(settledRow),
                                settledRow.getInteger("attempt") + 1,
                                new ReplyGraph.Reserved("reserved-settle-crash"))));
                assertThat(run.graph.replyProbe().outcomes.stream()
                                .filter(outcome ->
                                        outcome == dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.DUPLICATE)
                                .count())
                        .isEqualTo(duplicates + 1);
                assertThat(run.signals(id)).hasSize(1);
            } finally {
                ReplyGraph.await(run.pool
                        .query("DROP TRIGGER refuse_first_settle ON outbox")
                        .execute());
                ReplyGraph.await(
                        run.pool.query("DROP FUNCTION refuse_first_settle()").execute());
                ReplyGraph.await(
                        run.pool.query("DROP SEQUENCE refuse_settle_seq").execute());
            }
        }
    }

    @Test
    void finalFailureTakesTheFailureRouteWithNoExceptionText(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.limitAttempts();
            try {
                for (String ref : java.util.List.of("exhaust", "permanent", "decode")) {
                    WorkflowInstanceId id = run.startCall(ref);
                    run.until(
                            "the failed row to settle",
                            () -> run.row(id) != null
                                    && "DEAD_LETTER".equals(run.row(id).getString("state")));
                    run.until("the failure route", () -> "COMPLETED"
                            .equals(run.instance(id).getString("status")));
                    assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                            .isEqualTo(ref.equals("permanent") ? "PERMANENT:1" : "RETRIES_EXHAUSTED:2");
                    assertThat(run
                                    .rows(
                                            "SELECT payload_json::text FROM workflow_history WHERE workflow_id = $1",
                                            id.value())
                                    .stream()
                                    .map(row -> row.getString(0))
                                    .toList()
                                    .toString())
                            .doesNotContain("SECRET-77");
                }
            } finally {
                run.removeAttemptLimit();
            }
        }
    }

    @Test
    void failedReplyNeverLeaksItsHandleOrPayloadInRelayDiagnostics(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            ReplyGraph.await(run.pool.query("""
                    CREATE FUNCTION refuse_redaction_reply() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.kind = 'SIGNAL' THEN RAISE EXCEPTION 'record interrupted'; END IF;
                        RETURN NEW;
                    END $$
                    """).execute());
            ReplyGraph.await(run.pool.query("""
                    CREATE TRIGGER refuse_redaction_reply BEFORE INSERT ON workflow_commands
                    FOR EACH ROW EXECUTE FUNCTION refuse_redaction_reply()
                    """).execute());
            try {
                WorkflowInstanceId id = run.startLater("redaction");
                String handle = run.storedHandle("redaction", 0);
                run.until(
                        "permanent failure to dead-letter",
                        () -> run.row(id) != null
                                && "DEAD_LETTER".equals(run.row(id).getString("state")));
                assertThat(run.rows("SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", id.value()))
                        .hasSize(1);
                String lastError = run.row(id).getString("last_error");
                assertThat(lastError).isNotNull();
                java.util.List<ILoggingEvent> snapshot;
                synchronized (captured) {
                    snapshot = java.util.List.copyOf(captured.list);
                }
                String logs = snapshot.stream()
                        .map(ServiceReplyIT::logText)
                        .collect(java.util.stream.Collectors.joining("\n"));
                assertThat(lastError + "\n" + logs).doesNotContain("SECRET-77", handle);
            } finally {
                ReplyGraph.await(run.pool
                        .query("DROP TRIGGER refuse_redaction_reply ON workflow_commands")
                        .execute());
                ReplyGraph.await(
                        run.pool.query("DROP FUNCTION refuse_redaction_reply()").execute());
            }
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void fatalReplyNotifiesSupervisorWithoutLeakingHandleOrPayload(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.startLater("fatal-redaction");
            String handle = run.storedHandle("fatal-redaction", 0);
            run.until("fatal error notification", () -> logSnapshot(captured).stream()
                    .anyMatch(event -> event.getLoggerName().equals("dev.vertique.deploy.VerticleSupervisor")
                            && event.getFormattedMessage().contains("Fatal error in verticle")));
            String logs = logSnapshot(captured).stream()
                    .map(ServiceReplyIT::logText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).doesNotContain("SECRET-FATAL", handle);
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void ordinaryFatalServiceErrorKeepsItsSupervisorNotification(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination("inventory.later")
                    .eventType("later")
                    .payload(new ReplyGraph.Start("fatal-ordinary"))
                    .maxAttempts(1)
                    .build();
            ReplyGraph.await(
                    run.pool.withTransaction(tx -> run.graph.outboxService().publish(tx, entry)));
            run.until("ordinary fatal error notification", () -> logSnapshot(captured).stream()
                    .anyMatch(event -> event.getLoggerName().equals("dev.vertique.deploy.VerticleSupervisor")
                            && event.getFormattedMessage().contains("Fatal error in verticle")));
            assertThat(logSnapshot(captured).stream()
                            .filter(event -> event.getLoggerName().equals("dev.vertique.deploy.VerticleSupervisor"))
                            .map(ServiceReplyIT::logText))
                    .anyMatch(text -> text.contains("SECRET-ORDINARY"));
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void replyInterceptorFailureDoesNotLogHandleOrServiceMessage(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.startLater("callback-reply");
            String handle = run.storedHandle("callback-reply", 0);
            run.until("failed interceptor diagnostic", () -> logSnapshot(captured).stream()
                    .anyMatch(
                            event -> event.getLoggerName().equals("dev.vertique.services.dispatch.ServiceMethodInvoker")
                                    && event.getFormattedMessage().contains("afterDispatch interceptor")));
            String logs = logSnapshot(captured).stream()
                    .map(ServiceReplyIT::logText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).doesNotContain("SECRET-CALLBACK", handle);
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void ordinaryInterceptorFailureKeepsItsDiagnostic(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination("inventory.later")
                    .eventType("later")
                    .payload(new ReplyGraph.Start("callback-ordinary"))
                    .maxAttempts(1)
                    .build();
            ReplyGraph.await(
                    run.pool.withTransaction(tx -> run.graph.outboxService().publish(tx, entry)));
            run.until("ordinary failed interceptor diagnostic", () -> logSnapshot(captured).stream()
                    .anyMatch(
                            event -> event.getLoggerName().equals("dev.vertique.services.dispatch.ServiceMethodInvoker")
                                    && event.getFormattedMessage().contains("afterDispatch interceptor")));
            assertThat(logSnapshot(captured).stream()
                            .filter(event ->
                                    event.getLoggerName().equals("dev.vertique.services.dispatch.ServiceMethodInvoker"))
                            .filter(event -> event.getFormattedMessage().contains("afterDispatch interceptor"))
                            .map(ServiceReplyIT::logText))
                    .anyMatch(text -> text.contains("SECRET-CALLBACK"));
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void foreignTerminalFailureOnReplyDoesNotLeakValues(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.startLater("foreign-terminal-reply");
            String handle = run.storedHandle("foreign-terminal-reply", 0);
            run.until(
                    "foreign terminal callback",
                    () -> run.graph.replyProbe().terminalContext.get() != null);
            assertThat(run.graph.replyProbe().foreignContext.get())
                    .isNotNull()
                    .isNotSameAs(run.graph.replyProbe().dispatchContext.get());
            assertThat(run.graph.replyProbe().terminalContext.get())
                    .isSameAs(run.graph.replyProbe().dispatchContext.get());
            run.until("terminal failure diagnostic", () -> logSnapshot(captured).stream()
                    .anyMatch(event -> (event.getThrowableProxy() != null
                                    && event.getThrowableProxy().getClassName().equals(AssertionError.class.getName()))
                            || (event.getLoggerName().equals("dev.vertique.services.dispatch.ServiceMethodInvoker")
                                    && event.getFormattedMessage().contains("onTerminalComplete interceptor"))));
            String logs = logSnapshot(captured).stream()
                    .map(ServiceReplyIT::logText)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).doesNotContain("SECRET-TERMINAL", handle);
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void foreignTerminalFailureOnOrdinaryDispatchKeepsDiagnostics(Vertx vertx) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination("inventory.later")
                    .eventType("later")
                    .payload(new ReplyGraph.Start("foreign-terminal-ordinary"))
                    .maxAttempts(1)
                    .build();
            ReplyGraph.await(
                    run.pool.withTransaction(tx -> run.graph.outboxService().publish(tx, entry)));
            run.until("ordinary terminal failure diagnostic", () -> logSnapshot(captured).stream()
                    .map(ServiceReplyIT::logText)
                    .anyMatch(text -> text.contains("SECRET-TERMINAL")));
            assertThat(run.graph.replyProbe().foreignContext.get())
                    .isNotNull()
                    .isNotSameAs(run.graph.replyProbe().dispatchContext.get());
            assertThat(run.graph.replyProbe().terminalContext.get())
                    .isSameAs(run.graph.replyProbe().dispatchContext.get());
            assertThat(logSnapshot(captured).stream().map(ServiceReplyIT::logText))
                    .anyMatch(text -> text.contains("SECRET-TERMINAL"));
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    private static java.util.List<ILoggingEvent> logSnapshot(ListAppender<ILoggingEvent> captured) {
        synchronized (captured) {
            return java.util.List.copyOf(captured.list);
        }
    }

    private static String logText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (var cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
            text.append(' ').append(cause.getMessage());
        }
        return text.toString();
    }

    @Test
    void replyOverTheBoundTakesTheFailureRoute(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId id = run.startCall("oversize");
            run.until(
                    "the failure route or a second service call",
                    () -> run.graph.inventoryProbe().calls.get() > 1
                            || "COMPLETED".equals(run.instance(id).getString("status")));
            assertThat(run.graph.inventoryProbe().calls).hasValue(1);
            run.until("the refused reply to take its failure route", () -> "COMPLETED"
                    .equals(run.instance(id).getString("status")));
            run.until(
                    "the row to publish",
                    () -> run.row(id) != null && "PUBLISHED".equals(run.row(id).getString("state")));
            assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                    .isEqualTo("REPLY_REFUSED:1");
            assertThat(run.signals(id)).hasSize(1);
            assertThat(run.graph.replyProbe().outcomes)
                    .contains(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.REFUSED);
        }
    }

    @Test
    void nulReplyIsRefusedWithoutCallingTheServiceAgain(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            WorkflowInstanceId id = run.startCall("nul");
            run.until(
                    "failure route or a second call",
                    () -> run.graph.inventoryProbe().calls.get() > 1
                            || "COMPLETED".equals(run.instance(id).getString("status")));
            assertThat(run.graph.inventoryProbe().calls).hasValue(1);
            assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                    .isEqualTo("REPLY_REFUSED:1");
            run.until("row published", () -> "PUBLISHED".equals(run.row(id).getString("state")));
        }
    }

    @Test
    void recordedReplyReachingEndedInstanceSettlesStale(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.graph.inventoryProbe().held = Promise.promise();
            WorkflowInstanceId id = run.startCall("ended-before-application");
            run.until("service called", () -> run.graph.inventoryProbe().calls.get() == 1);
            run.pauseExecutor();
            try {
                run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("answer"));
                run.until("reply recorded", () -> run.signals(id).size() == 1);
                // Model the ended instance and closed fence while this already-recorded command waits.
                ReplyGraph.await(run.pool.withTransaction(tx -> tx.preparedQuery(
                                "DELETE FROM workflow_signal_waits WHERE instance_id=$1")
                        .execute(io.vertx.sqlclient.Tuple.of(id.value()))
                        .compose(v -> tx.preparedQuery("UPDATE workflow_instances SET status='COMPLETED' WHERE id=$1")
                                .execute(io.vertx.sqlclient.Tuple.of(id.value())))));
            } finally {
                run.resumeExecutor();
            }
            run.until("reply stale", () -> "STALE"
                    .equals(run.signals(id).getFirst().getString("state")));
        }
    }

    @Test
    void failedRecordingRetriesAndOnTheLastAttemptLeavesTheWaitOpen(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.limitAttempts();
            ReplyGraph.await(
                    run.pool.query("CREATE SEQUENCE refuse_command_seq").execute());
            ReplyGraph.await(run.pool.query("""
                    CREATE FUNCTION refuse_command() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.kind = 'SIGNAL' AND nextval('refuse_command_seq') <= 1 THEN
                            RAISE EXCEPTION 'record interrupted';
                        END IF;
                        RETURN NEW;
                    END $$
                    """).execute());
            ReplyGraph.await(run.pool
                    .query(
                            "CREATE TRIGGER refuse_command BEFORE INSERT ON workflow_commands FOR EACH ROW EXECUTE FUNCTION refuse_command()")
                    .execute());
            try {
                WorkflowInstanceId first = run.startCall("record-retry");
                run.until(
                        "the reply to complete or its row to publish",
                        () -> "COMPLETED".equals(run.instance(first).getString("status"))
                                || (run.row(first) != null
                                        && "PUBLISHED".equals(run.row(first).getString("state"))));
                assertThat(run.graph.inventoryProbe().calls).hasValue(2);
                run.until("the retried reply to complete", () -> "COMPLETED"
                        .equals(run.instance(first).getString("status")));
                run.until(
                        "the retried row to publish",
                        () -> run.row(first) != null
                                && "PUBLISHED".equals(run.row(first).getString("state")));
                assertThat(ReplyGraph.json(run.instance(first), "state_json").getString("result"))
                        .isEqualTo("reserved-record-retry");
                assertThat(run.signals(first)).hasSize(1);
                ReplyGraph.await(run.pool.query("""
                        CREATE OR REPLACE FUNCTION refuse_command() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.kind = 'SIGNAL' THEN RAISE EXCEPTION 'record interrupted'; END IF;
                            RETURN NEW;
                        END $$
                        """).execute());
                WorkflowInstanceId lost = run.start("service-reply-long-timed", "record-lost");
                run.until(
                        "the failed row to dead letter",
                        () -> run.row(lost) != null
                                && "DEAD_LETTER".equals(run.row(lost).getString("state")));
                assertThat(run.signals(lost)).isEmpty();
                assertThat(run.rows("SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", lost.value()))
                        .hasSize(1);
                assertThat(run.rows(
                                "SELECT timer_id FROM workflow_timers WHERE workflow_id = $1 AND status = 'SCHEDULED' AND fire_at > now() + interval '30 minutes'",
                                lost.value()))
                        .hasSize(1);
                ReplyGraph.await(run.pool.withTransaction(tx -> tx.preparedQuery(
                                "UPDATE workflow_timers SET fire_at = now() - interval '1 second' WHERE workflow_id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(lost.value()))
                        .compose(ignored -> tx.preparedQuery(
                                        "UPDATE workflow_commands SET fire_at = now() - interval '1 second', available_at = now() - interval '1 second' WHERE workflow_id = $1 AND kind = 'TIMER'")
                                .execute(io.vertx.sqlclient.Tuple.of(lost.value())))));
                run.until("the wait timeout", () -> "COMPLETED"
                        .equals(run.instance(lost).getString("status")));
                assertThat(ReplyGraph.json(run.instance(lost), "state_json").getString("result"))
                        .isEqualTo("timeout");
            } finally {
                ReplyGraph.await(run.pool
                        .query("DROP TRIGGER refuse_command ON workflow_commands")
                        .execute());
                ReplyGraph.await(
                        run.pool.query("DROP FUNCTION refuse_command()").execute());
                ReplyGraph.await(
                        run.pool.query("DROP SEQUENCE refuse_command_seq").execute());
                run.removeAttemptLimit();
            }
        }
    }

    @Test
    void closedWaitSettlesTheRowAndCountsItStale(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            run.graph.inventoryProbe().held = Promise.promise();
            WorkflowInstanceId id = run.startCall("cancel-in-flight");
            run.until("the service call", () -> run.graph.inventoryProbe().calls.get() == 1);
            ReplyGraph.await(run.pool.withTransaction(
                    tx -> run.graph.transactionalOperations().cancel(id, "cancel", tx)));
            run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("late"));
            run.until("the cancelled call to settle", () -> "PUBLISHED"
                    .equals(run.row(id).getString("state")));
            assertThat(run.signals(id)).isEmpty();
            assertThat(run.instance(id).getString("status")).isEqualTo("CANCELLED");
            assertThat(run.row(id).getInteger("attempt")).isZero();
            assertThat(run.graph.replyProbe().outcomes)
                    .contains(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE);
            for (boolean fail : new boolean[] {false, true}) {
                int before = run.graph.inventoryProbe().calls.get();
                run.graph.inventoryProbe().held = Promise.promise();
                WorkflowInstanceId timed = run.start("service-reply-timed", fail ? "timed-failure" : "timed-success");
                run.until(
                        "the service call",
                        () -> run.graph.inventoryProbe().calls.get() > before);
                run.until("the timer to close the wait", () -> "COMPLETED"
                        .equals(run.instance(timed).getString("status")));
                if (fail) run.graph.inventoryProbe().held.fail(new IllegalStateException("late"));
                else run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("late"));
                run.until("the late row to publish", () -> "PUBLISHED"
                        .equals(run.row(timed).getString("state")));
                assertThat(run.signals(timed)).isEmpty();
                if (!fail) assertThat(run.row(timed).getInteger("attempt")).isZero();
                assertThat(ReplyGraph.json(run.instance(timed), "state_json").getString("result"))
                        .isEqualTo("timeout");
            }
            run.graph.inventoryProbe().held = Promise.promise();
            run.graph.inventoryProbe().heldSecond = Promise.promise();
            int priorCalls = run.graph.inventoryProbe().calls.get();
            WorkflowInstanceId looped = run.start("service-reply-loop", "looped");
            run.until(
                    "the first and second entries to call the service",
                    () -> run.graph.inventoryProbe().calls.get() >= priorCalls + 2);
            var waits = run.rows("SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", looped.value());
            assertThat(waits).hasSize(1);
            Object currentWait = waits.getFirst().getValue("wait_id");
            run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("old"));
            run.until(
                    "the old row to settle",
                    () -> run.rows(
                                            "SELECT id FROM outbox WHERE headers->>'x-workflow-id' = $1 AND state = 'PUBLISHED'",
                                            looped.value().toString())
                                    .size()
                            == 1);
            assertThat(run.signals(looped)).isEmpty();
            var oldRow = run.rows(
                            "SELECT attempt FROM outbox WHERE headers->>'x-workflow-id' = $1 AND state = 'PUBLISHED'",
                            looped.value().toString())
                    .getFirst();
            assertThat(oldRow.getInteger("attempt")).isZero();
            assertThat(run.rows("SELECT wait_id FROM workflow_signal_waits WHERE instance_id = $1", looped.value())
                            .getFirst()
                            .getValue("wait_id"))
                    .isEqualTo(currentWait);
            run.graph.inventoryProbe().heldSecond.complete(new ReplyGraph.Reserved("new"));
            run.until("the new reply to apply", () -> "COMPLETED"
                    .equals(run.instance(looped).getString("status")));
            assertThat(ReplyGraph.json(run.instance(looped), "state_json").getString("result"))
                    .isEqualTo("new");
            run.graph.inventoryProbe().held = null;
            run.graph.inventoryProbe().heldSecond = null;
            ReplyGraph.await(run.pool.query("""
                    CREATE FUNCTION delay_reply_row() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.payload->>'reference' = 'delayed' THEN NEW.available_at := now() + interval '5 seconds'; END IF;
                        RETURN NEW;
                    END $$
                    """).execute());
            ReplyGraph.await(run.pool
                    .query(
                            "CREATE TRIGGER delay_reply_row BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION delay_reply_row()")
                    .execute());
            try {
                int before = run.graph.inventoryProbe().calls.get();
                long staleBefore = run.graph.replyProbe().outcomes.stream()
                        .filter(o -> o == dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE)
                        .count();
                WorkflowInstanceId delayed = run.start("service-reply-timed", "delayed");
                run.until(
                        "the delayed row to publish after timeout",
                        () -> run.row(delayed) != null
                                && "PUBLISHED".equals(run.row(delayed).getString("state")));
                assertThat(run.graph.inventoryProbe().calls).hasValue(before);
                assertThat(run.graph.replyProbe().outcomes.stream()
                                .filter(o -> o == dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.STALE)
                                .count())
                        .isEqualTo(staleBefore + 1);
                assertThat(ReplyGraph.json(run.instance(delayed), "state_json").getString("result"))
                        .isEqualTo("timeout");
            } finally {
                ReplyGraph.await(
                        run.pool.query("DROP TRIGGER delay_reply_row ON outbox").execute());
                ReplyGraph.await(
                        run.pool.query("DROP FUNCTION delay_reply_row()").execute());
            }
        }
    }

    @Test
    void parkedReplySettlesWhenItsWaitCloses(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db)) {
            for (boolean cancel : new boolean[] {false, true}) {
                run.graph.inventoryProbe().held = Promise.promise();
                int before = run.graph.inventoryProbe().calls.get();
                WorkflowInstanceId id = run.start("service-reply-timed", cancel ? "park-cancel" : "park-timeout");
                run.until(
                        "the service call",
                        () -> run.graph.inventoryProbe().calls.get() > before);
                ReplyGraph.await(run.pool
                        .preparedQuery("UPDATE workflow_instances SET status = 'FAILED' WHERE id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(id.value())));
                run.until("the timer to become due", () -> !run.rows(
                                "SELECT timer_id FROM workflow_timers WHERE workflow_id = $1 AND fire_at < now()",
                                id.value())
                        .isEmpty());
                run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("late"));
                run.until("the reply command to park", () -> run.signals(id).stream()
                        .anyMatch(row -> "PARKED".equals(row.getString("state"))));
                if (cancel) {
                    ReplyGraph.await(run.pool.withTransaction(
                            tx -> run.graph.transactionalOperations().cancel(id, "cancel", tx)));
                    assertThat(run.signals(id)).singleElement().satisfies(row -> assertThat(row.getString("state"))
                            .isEqualTo("CANCELLED"));
                } else {
                    ReplyGraph.await(run.pool.withTransaction(
                            tx -> run.graph.transactionalOperations().retry(id, tx)));
                    run.until("the due timeout to settle the parked reply", () -> run.signals(id).stream()
                            .anyMatch(row -> "STALE".equals(row.getString("state"))));
                    assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                            .isEqualTo("timeout");
                }
            }
        }
    }

    @Test
    void firstOutcomeStandsAcrossALeaseExpiry(Vertx vertx) {
        try (ReplyGraph run = new ReplyGraph(vertx, db, 3000)) {
            run.graph.inventoryProbe().held = Promise.promise();
            run.graph.inventoryProbe().heldSecond = Promise.promise();
            WorkflowInstanceId id = run.startCall("lease-first");
            run.until("the first call", () -> run.graph.inventoryProbe().calls.get() == 1);
            run.pauseExecutor();
            run.until("the first lease to expire", () -> !run.rows(
                            "SELECT id FROM outbox WHERE id = $1 AND claimed_at < now() - interval '1 second'",
                            run.row(id).getLong("id"))
                    .isEmpty());
            ReplyGraph.await(run.graph.outboxRepository().reclaimStale(Duration.ofSeconds(1)));
            run.until(
                    "a second call on the reclaimed row",
                    () -> run.graph.inventoryProbe().calls.get() == 2);
            run.graph.inventoryProbe().held.complete(new ReplyGraph.Reserved("first"));
            run.until("the first outcome to be recorded", () -> run.signals(id).size() == 1);
            run.graph.inventoryProbe().heldSecond.complete(new ReplyGraph.Reserved("second"));
            run.until("the reclaimed row to publish", () -> "PUBLISHED"
                    .equals(run.row(id).getString("state")));
            run.until("the second outcome to be duplicate", () -> run.graph
                    .replyProbe()
                    .outcomes
                    .contains(dev.vertique.workflow.ops.ReplyOutcomeObserver.Outcome.DUPLICATE));
            assertThat(run.signals(id)).hasSize(1);
            run.resumeExecutor();
            run.until("the first outcome to apply", () -> "COMPLETED"
                    .equals(run.instance(id).getString("status")));
            assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                    .isEqualTo("first");
        }
        try (ReplyGraph run = new ReplyGraph(vertx, db, 3000)) {
            run.limitAttempts();
            ReplyGraph.await(
                    run.pool.query("CREATE SEQUENCE refuse_dead_letter_seq").execute());
            ReplyGraph.await(run.pool.query("""
                    CREATE FUNCTION refuse_dead_letter() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.state = 'DEAD_LETTER' AND nextval('refuse_dead_letter_seq') = 1 THEN
                            RAISE EXCEPTION 'settle interrupted';
                        END IF;
                        RETURN NEW;
                    END $$
                    """).execute());
            ReplyGraph.await(run.pool
                    .query(
                            "CREATE TRIGGER refuse_dead_letter BEFORE UPDATE ON outbox FOR EACH ROW EXECUTE FUNCTION refuse_dead_letter()")
                    .execute());
            try {
                run.graph.inventoryProbe().held = Promise.promise();
                WorkflowInstanceId id = run.startCall("failure-then-success");
                run.until(
                        "the first call", () -> run.graph.inventoryProbe().calls.get() == 1);
                run.pauseExecutor();
                run.graph.inventoryProbe().held.fail(new IllegalStateException("retry"));
                run.until(
                        "the final failure to be recorded while settling is interrupted",
                        () -> run.graph.inventoryProbe().calls.get() == 2
                                && run.signals(id).size() == 1
                                && "PROCESSING".equals(run.row(id).getString("state")));
                run.until("the second lease to expire", () -> !run.rows(
                                "SELECT id FROM outbox WHERE id = $1 AND claimed_at < now() - interval '1 second'",
                                run.row(id).getLong("id"))
                        .isEmpty());
                ReplyGraph.await(run.graph.outboxRepository().reclaimStale(Duration.ofSeconds(1)));
                run.until("the success after reclaim to settle", () -> "PUBLISHED"
                        .equals(run.row(id).getString("state")));
                assertThat(run.graph.inventoryProbe().calls).hasValue(3);
                assertThat(run.signals(id)).hasSize(1);
                run.resumeExecutor();
                run.until("the earlier failure to apply", () -> "COMPLETED"
                        .equals(run.instance(id).getString("status")));
                assertThat(ReplyGraph.json(run.instance(id), "state_json").getString("result"))
                        .isEqualTo("RETRIES_EXHAUSTED:2");
            } finally {
                ReplyGraph.await(run.pool
                        .query("DROP TRIGGER refuse_dead_letter ON outbox")
                        .execute());
                ReplyGraph.await(
                        run.pool.query("DROP FUNCTION refuse_dead_letter()").execute());
                ReplyGraph.await(
                        run.pool.query("DROP SEQUENCE refuse_dead_letter_seq").execute());
                run.removeAttemptLimit();
            }
        }
    }
}
