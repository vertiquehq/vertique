// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.RelayGraph.DEFINITION_ID;
import static dev.vertique.workflow.postgresql.engine.RelayGraph.EVENT_TYPE;
import static dev.vertique.workflow.postgresql.engine.RelayGraph.SIGNAL;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertxModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.ServiceIdentityResolver;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Contract;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Graph;
import dev.vertique.workflow.postgresql.engine.RelayGraph.Start;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies how a signal that travels through the outbox relay is attributed: to the identity the relay's reconstruction
 * binds, with the original caller as the party it acts for; that a snapshot that does not verify records nothing under
 * the default policy and a degraded attribution when the policy continues; and that a delivery without identity
 * carriage is logged as no actor.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class RelayedSignalActorIT {

    // The container starts empty; the three migration sets all begin at V1, so each gets a history table of its own.
    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_relayed_signal_actor_it");

    private static final Duration DELIVERY = Duration.ofSeconds(30);

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    private static void migrate(String location, String historyTable, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(location)
                .table(historyTable);
        if (baseline) {
            flyway.baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    // --- Tests ---

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a relayed signal is logged as the relay, acting for the user who wrote the outbox row")
    void relayedSignalIsLoggedAsTheRelayOnBehalfOfTheUser(Vertx vertx) {
        // Given identity carriage installed with a signing key, and user u-9 whose transaction signals instance X
        try (Run run = Run.carrying(vertx, null)) {
            WorkflowInstanceId x = run.waitingInstance("relay-default");

            // When the relay delivers it with default wiring
            run.publishSignal(user("u-9"), x, "default-wiring", false);
            Attributed signal = run.awaitSignal(x);

            // Then the recorded signal carries the system with the relay's reason, acting for u-9, verified
            assertThat(signal.actorKind()).isEqualTo("SYSTEM");
            assertThat(signal.actorReason()).isEqualTo(EVENT_TYPE);
            assertThat(signal.onBehalfOf()).isEqualTo("u-9");
            assertThat(signal.attribution()).isEqualTo("VERIFIED");
        }

        // And with a service identity resolver bound to relay-svc (second graph)
        ServiceIdentityResolver relayService =
                origin -> SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "relay-svc", Map.of()));
        try (Run run = Run.carryingWithResolver(vertx, relayService)) {
            WorkflowInstanceId y = run.waitingInstance("relay-resolver");
            run.publishSignal(user("u-9"), y, "resolver-wiring", false);
            Attributed signal = run.awaitSignal(y);

            // Then the recorded signal carries SERVICE relay-svc, acting for u-9, verified
            assertThat(signal.actorKind()).isEqualTo("SERVICE");
            assertThat(signal.actorId()).isEqualTo("relay-svc");
            assertThat(signal.onBehalfOf()).isEqualTo("u-9");
            assertThat(signal.attribution()).isEqualTo("VERIFIED");
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName(
            "an unverifiable snapshot records nothing under the default policy and a delivery without carriage logs none")
    void unverifiableSnapshotRecordsNothingAndNoCarriageLogsNone(Vertx vertx) {
        // Given a tampered snapshot and the default degradation policy
        try (Run run = Run.carrying(vertx, null)) {
            WorkflowInstanceId refused = run.waitingInstance("tampered-default");

            // When the relay delivers it
            run.publishSignal(user("u-9"), refused, "tampered-fail", true);
            run.awaitFailedDelivery();

            // Then the delivery failed and no command, dedup or inbox row survives
            assertThat(run.signalCommands(refused)).isEmpty();
            assertThat(run.dedupRows(refused)).isZero();
            assertThat(run.inboxRows(refused)).isZero();
        }

        // And with the policy set to continue, the same delivery is recorded as degraded and acts for no one
        try (Run run = Run.carrying(vertx, "CONTINUE_WITHOUT_IDENTITY")) {
            WorkflowInstanceId continued = run.waitingInstance("tampered-continue");
            run.publishSignal(user("u-9"), continued, "tampered-continue", true);
            Attributed signal = run.awaitSignal(continued);

            assertThat(signal.attribution()).isEqualTo("DEGRADED");
            assertThat(signal.onBehalfOf()).isNull();
            assertThat(signal.actorKind()).isEqualTo("SYSTEM");
            assertThat(signal.actorReason()).isEqualTo(EVENT_TYPE);
        }

        // And without identity carriage the signal is recorded with NONE and absent
        try (Run run = Run.plain(vertx)) {
            WorkflowInstanceId plain = run.waitingInstance("no-carriage");
            run.publishSignal(user("u-9"), plain, "no-carriage-key", false);
            Attributed signal = run.awaitSignal(plain);

            assertThat(signal.actorKind()).isEqualTo("NONE");
            assertThat(signal.actorId()).isNull();
            assertThat(signal.actorReason()).isNull();
            assertThat(signal.onBehalfOf()).isNull();
            assertThat(signal.attribution()).isEqualTo("ABSENT");
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("a segment's intent carries the owner's attribution, which the relay does not treat as identity")
    void segmentIntentCarriesAttributionThatTheRelayDoesNotTreatAsIdentity(Vertx vertx) {
        // Given identity carriage with the outbox relay requiring it, the default degradation policy, and a
        // definition whose first step dispatches billing.charge
        try (Run run = Run.carryingRequiring(vertx, Map.of("outbox-relay", "REQUIRED"))) {
            // When user U records the start with no party acted for, the executor runs the segment and the relay
            // delivers the outbox record
            WorkflowInstanceId charge = run.startCharge(user("U"), "attribution");
            run.awaitChargeSettled();
            JsonObject headers = run.chargeHeaders();

            // Then the record's headers name U as the actor, verified
            assertThat(headers.getString("x-workflow-actor-kind")).isEqualTo("USER");
            assertThat(headers.getString("x-workflow-actor-id")).isEqualTo("U");
            assertThat(headers.getString("x-workflow-attribution")).isEqualTo("VERIFIED");

            // And no party acted for, no reason, and nothing an application could have set, appears in any letter case
            assertThat(headers.fieldNames())
                    .extracting(name -> name.toLowerCase(Locale.ROOT))
                    .doesNotContain("x-workflow-on-behalf-of", "x-workflow-actor-reason", "x-workflow-custom");
            assertThat(headers.encode()).doesNotContain("mallory").doesNotContain("eve");

            // And the workflow's own correlation headers are still there
            assertThat(headers.getString("x-workflow-id"))
                    .isEqualTo(charge.value().toString());

            // And the relay found no identity snapshot where one was required, so the delivery was refused under the
            // default policy and the downstream operation never ran as U
            assertThat(run.chargeState()).isNotEqualTo("PUBLISHED");
            assertThat(run.billing().principals()).isEmpty();
            assertThat(run.billing().withoutContext()).isEmpty();
        }
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("a segment's service dispatch runs downstream as the relay identity, not as the user who recorded it")
    void segmentDispatchRunsDownstreamAsTheRelayIdentityNotTheRecordingUser(Vertx vertx) {
        // Given identity carriage with the default requirements and user U who records the start
        try (Run run = Run.carrying(vertx, null)) {
            // When the executor runs the segment and the relay delivers the record with default wiring
            run.startCharge(user("U"), "relay-default");
            run.awaitChargeSettled();

            // Then the downstream operation ran as the system with the relay's reason, never as U
            assertThat(run.billing().principals()).singleElement().isEqualTo(new WorkflowActor.System("charge"));
        }

        // And with a service identity resolver bound to relay-svc (second graph) it ran as that service
        ServiceIdentityResolver relayService =
                origin -> SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "relay-svc", Map.of()));
        try (Run run = Run.carryingWithResolver(vertx, relayService)) {
            run.startCharge(user("U"), "relay-resolver");
            run.awaitChargeSettled();

            assertThat(run.billing().principals()).singleElement().isEqualTo(new WorkflowActor.Service("relay-svc"));
        }
    }

    // --- Fixture ---

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    /** The actor columns of one command. */
    private record Attributed(
            String actorKind, String actorId, String actorReason, String onBehalfOf, String attribution) {}

    /** One started graph over the database with the means to write outbox rows and read what the relay recorded. */
    private static final class Run implements AutoCloseable {
        private final Vertx vertx;
        private final Pool pool;
        private final Graph graph;
        private final VertiqueApplicationHandle<?> handle;
        private final Supplier<Optional<IdentitySnapshotCapture>> capture;

        private Run(
                Vertx vertx,
                Pool pool,
                Graph graph,
                VertiqueApplicationHandle<?> handle,
                Supplier<Optional<IdentitySnapshotCapture>> capture) {
            this.vertx = vertx;
            this.pool = pool;
            this.graph = graph;
            this.handle = handle;
            this.capture = capture;
        }

        static Run carrying(Vertx vertx, String degradationPolicy) {
            reset(vertx);
            PgConnectOptions options = RelayGraph.options(db);
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            RelayGraph.Carrying graph = DaggerRelayGraph_Carrying.builder()
                    .vertxModule(new VertxModule(vertx, RelayGraph.config(degradationPolicy)))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .build();
            return started(vertx, pool, graph, () -> Optional.of(graph.identitySnapshotCapture()));
        }

        static Run carryingRequiring(Vertx vertx, Map<String, String> carriageRequirements) {
            reset(vertx);
            PgConnectOptions options = RelayGraph.options(db);
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            RelayGraph.Carrying graph = DaggerRelayGraph_Carrying.builder()
                    .vertxModule(new VertxModule(vertx, RelayGraph.config(null, carriageRequirements)))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .build();
            return started(vertx, pool, graph, () -> Optional.of(graph.identitySnapshotCapture()));
        }

        static Run carryingWithResolver(Vertx vertx, ServiceIdentityResolver resolver) {
            reset(vertx);
            PgConnectOptions options = RelayGraph.options(db);
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            RelayGraph.CarryingWithResolver graph = DaggerRelayGraph_CarryingWithResolver.builder()
                    .vertxModule(new VertxModule(vertx, RelayGraph.config(null)))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .resolverModule(new RelayGraph.ResolverModule(resolver))
                    .build();
            return started(vertx, pool, graph, () -> Optional.of(graph.identitySnapshotCapture()));
        }

        static Run plain(Vertx vertx) {
            reset(vertx);
            PgConnectOptions options = RelayGraph.options(db);
            Pool pool = RelayGraph.pool(vertx, db.toPoolConfig(), options);
            RelayGraph.Plain graph = DaggerRelayGraph_Plain.builder()
                    .vertxModule(new VertxModule(vertx, RelayGraph.config(null)))
                    .infrastructure(new RelayGraph.Infrastructure(pool, options))
                    .build();
            return started(vertx, pool, graph, Optional::empty);
        }

        /** Clears what an earlier graph of the test left in the outbox and the inbox. */
        private static void reset(Vertx vertx) {
            Pool admin = RelayGraph.pool(vertx, db.toPoolConfig(), RelayGraph.options(db));
            try {
                await(admin.query("DELETE FROM outbox").execute());
                await(admin.query("DELETE FROM inbox").execute());
            } finally {
                await(admin.close());
            }
        }

        private static Run started(
                Vertx vertx, Pool pool, Graph graph, Supplier<Optional<IdentitySnapshotCapture>> capture) {
            VertiqueApplicationHandle<?> handle;
            try {
                handle = await(RelayGraph.start(vertx, graph), Duration.ofSeconds(45));
            } catch (RuntimeException | Error failure) {
                await(pool.close());
                throw failure;
            }
            return new Run(vertx, pool, graph, handle, capture);
        }

        /** Starts an instance of the workflow and waits until it waits for its signal. */
        WorkflowInstanceId waitingInstance(String ref) {
            StartCommand command = new StartCommand(
                    DEFINITION_ID, Contract.class.getName(), new Start(ref), ref, null, null, null, null, null);
            WorkflowInstanceId id = await(
                    pool.withTransaction(tx -> graph.transactionalOperations().start(command, tx)));
            await(graph.executionProbe().awaitIdle(id, Duration.ofSeconds(15)));
            return id;
        }

        /** The downstream operation the relay delivers a segment's dispatch to. */
        BillingProbe billing() {
            return graph.billingProbe();
        }

        /**
         * Records the start of the dispatching workflow in a transaction of the caller's, with the caller's security
         * context bound, so that the executor runs its first segment afterwards without any identity.
         */
        WorkflowInstanceId startCharge(SecurityIdentity caller, String ref) {
            StartCommand command = new StartCommand(
                    RelayGraph.CHARGE_DEFINITION_ID,
                    RelayGraph.ChargeContract.class.getName(),
                    new Start(ref),
                    ref,
                    null,
                    null,
                    null,
                    null,
                    null);
            return await(onDuplicatedContext(() -> {
                ContextHolder.Scope bound = graph.contextHolder().bind(SecurityContext.class, live(caller));
                return pool.withTransaction(
                                tx -> graph.transactionalOperations().start(command, tx))
                        .onComplete(ignored -> bound.close());
            }));
        }

        /** Waits until the relay has delivered the dispatched record or has failed to. */
        void awaitChargeSettled() {
            awaitCondition("the relay to settle the billing.charge record", () -> chargeRows().stream()
                    .anyMatch(
                            row -> row.getString("state").equals("PUBLISHED") || row.getString("last_error") != null));
        }

        /** The headers of the one outbox record the dispatch wrote. */
        JsonObject chargeHeaders() {
            return chargeRows().getFirst().getJsonObject("headers");
        }

        /** The state of the one outbox record the dispatch wrote. */
        String chargeState() {
            return chargeRows().getFirst().getString("state");
        }

        private List<io.vertx.sqlclient.Row> chargeRows() {
            List<io.vertx.sqlclient.Row> rows = new java.util.ArrayList<>();
            await(pool.query("SELECT state, last_error, headers FROM outbox WHERE destination = 'billing.charge'")
                            .execute())
                    .forEach(rows::add);
            return rows;
        }

        private static SecurityContext live(SecurityIdentity caller) {
            return SecurityContexts.assemble(
                    caller,
                    new AuthenticationState(
                            DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                    AuthorizationClaims.empty(),
                    Optional.empty());
        }

        /**
         * Writes, in a transaction of the user's, an outbox row that signals the instance through the signal endpoint.
         * The user's identity snapshot is captured as the row is written. With {@code tamper} the snapshot is altered
         * before the transaction commits, so that the relay can only ever see the altered row.
         */
        void publishSignal(SecurityIdentity caller, WorkflowInstanceId id, String key, boolean tamper) {
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination("workflow.signals.post")
                    .eventType(EVENT_TYPE)
                    .aggregateType("WorkflowInstance")
                    .aggregateId(id.value().toString())
                    .payload(WorkflowSignalRequest.instance(id, SIGNAL, Map.of("value", key), key))
                    .build();
            SecurityContext live = live(caller);
            await(onDuplicatedContext(() -> {
                ContextHolder.Scope captured =
                        capture.get().map(c -> c.captureFrom(live)).orElse(null);
                return pool.withTransaction(tx -> graph.outboxService()
                                .publish(tx, entry)
                                .compose(outboxId -> tamper ? tamperSnapshot(tx, outboxId) : Future.succeededFuture()))
                        .onComplete(ignored -> {
                            if (captured != null) {
                                captured.close();
                            }
                        });
            }));
        }

        /** Flips the last bit of the signed identity snapshot of the outbox row, inside the writer's transaction. */
        private static Future<Void> tamperSnapshot(SqlClient tx, long outboxId) {
            return tx.preparedQuery("SELECT metadata FROM outbox WHERE id = $1")
                    .execute(Tuple.of(outboxId))
                    .compose(rows -> {
                        Object value = rows.iterator().next().getValue("metadata");
                        JsonObject metadata =
                                value instanceof JsonObject json ? json : new JsonObject(String.valueOf(value));
                        JsonObject snapshot = metadata.getJsonObject("context").getJsonObject("identity-snapshot");
                        byte[] raw = Base64.getDecoder().decode(snapshot.getString("snapshot"));
                        raw[raw.length - 1] ^= 0x01;
                        snapshot.put("snapshot", Base64.getEncoder().encodeToString(raw));
                        return tx.preparedQuery("UPDATE outbox SET metadata = $2 WHERE id = $1")
                                .execute(Tuple.of(outboxId, metadata))
                                .<Void>mapEmpty();
                    });
        }

        /** Waits until the relay recorded the instance's signal and returns the actor columns it carries. */
        Attributed awaitSignal(WorkflowInstanceId id) {
            awaitCondition(
                    "the relay to record the signal", () -> !signalCommands(id).isEmpty());
            return signalCommands(id).getFirst();
        }

        /** Waits until the relay tried to deliver a row and failed. */
        void awaitFailedDelivery() {
            awaitCondition(
                    "the relay to fail a delivery",
                    () -> scalar(pool.query("SELECT count(*) FROM outbox WHERE last_error IS NOT NULL")
                                    .execute())
                            > 0);
        }

        List<Attributed> signalCommands(WorkflowInstanceId id) {
            List<Attributed> rows = new java.util.ArrayList<>();
            for (var row : await(
                    pool.preparedQuery("SELECT actor_kind, actor_id, actor_reason, on_behalf_of, attribution_status"
                                    + " FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL' ORDER BY seq")
                            .execute(Tuple.of(id.value())))) {
                rows.add(new Attributed(
                        row.getString("actor_kind"),
                        row.getString("actor_id"),
                        row.getString("actor_reason"),
                        row.getString("on_behalf_of"),
                        row.getString("attribution_status")));
            }
            return rows;
        }

        long dedupRows(WorkflowInstanceId id) {
            return scalar(
                    pool.preparedQuery("SELECT count(*) FROM workflow_dedup WHERE workflow_id = $1 AND kind = 'signal'")
                            .execute(Tuple.of(id.value())));
        }

        long inboxRows(WorkflowInstanceId id) {
            return scalar(pool.preparedQuery("SELECT count(*) FROM inbox WHERE message_id LIKE $1 || '%'")
                    .execute(Tuple.of(id.value().toString())));
        }

        private static long scalar(Future<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> query) {
            return await(query).iterator().next().getLong(0);
        }

        private void awaitCondition(String description, BooleanSupplier condition) {
            long deadline = System.nanoTime() + DELIVERY.toNanos();
            while (System.nanoTime() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while waiting for " + description, e);
                }
            }
            throw new AssertionError("timed out waiting for " + description);
        }

        private <T> Future<T> onDuplicatedContext(Supplier<Future<T>> work) {
            Promise<T> result = Promise.promise();
            ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            context.runOnContext(ignored -> {
                try {
                    work.get().onComplete(result);
                } catch (Throwable e) {
                    result.tryFail(e);
                }
            });
            return result.future();
        }

        @Override
        public void close() {
            try {
                await(handle.shutdown(), Duration.ofSeconds(30));
            } finally {
                await(pool.close());
            }
        }

        private static <T> T await(Future<T> future) {
            return await(future, Duration.ofSeconds(20));
        }

        private static <T> T await(Future<T> future, Duration timeout) {
            try {
                return future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError("the future failed", e.getCause());
            } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
                throw new AssertionError("the future did not complete in " + timeout, e);
            }
        }
    }
}
