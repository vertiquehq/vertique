// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.RelayCapabilities;
import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test that verifies the deployed {@link OutboxRelay} verticle's full poll-to-publish
 * runtime against a real PostgreSQL instance.
 *
 * <p>Exercises the complete relay lifecycle: {@code start} (including
 * {@code openListenConnection} for the {@link RelayStrategy#LISTEN_NOTIFY} strategy and
 * {@code schedulePoll}), one poll cycle that claims and delivers a pending entry via a capturing
 * handler, {@code markPublished} recording the {@code PUBLISHED} state, and {@code stop} tearing
 * down the LISTEN connection and cancelling the poll timer.
 *
 * <p>The delivery outcome is detected deterministically by polling the outbox row's {@code state}
 * column until it becomes {@code PUBLISHED}; no timing sleeps are used.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OutboxRelayIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("outbox_relay_it")
            .withMigration("classpath:db/migration/inbox-outbox");

    static Pool pool;
    static PgInboxOutboxRepository repository;
    static PgConnectOptions connectOptions;

    /** Deployment ID of the relay verticle; non-null during the test, cleared in afterEach. */
    private String deploymentId;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        connectOptions = new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password());
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
        repository = new PgInboxOutboxRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE outbox, inbox RESTART IDENTITY").execute().onComplete(ar -> ctx.completeNow());
    }

    @AfterEach
    void undeployRelay(Vertx vertx, VertxTestContext ctx) {
        // Guard against double-undeploy: the success path already undeploys; if deploymentId is
        // still set here, the test exited an abnormal path and we clean up.
        if (deploymentId != null) {
            String id = deploymentId;
            deploymentId = null;
            vertx.undeploy(id).onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Test ---

    @Test
    @DisplayName("relay delivers a pending KAFKA entry end-to-end and marks it PUBLISHED")
    void relayDeliversPendingEntryAndMarksPublished(Vertx vertx, VertxTestContext ctx) {
        // --- Capturing handler ---
        AtomicReference<OutboxEnvelope> captured = new AtomicReference<>();
        OutboxDestinationHandler handler = new CapturingKafkaHandler(captured);

        // --- Build relay ---
        Map<DestinationType, OutboxDestinationHandler> handlerMap = OutboxRelay.buildHandlerMap(Set.of(handler));
        RelayCapabilities caps = OutboxRelay.deriveCapabilities(handlerMap);

        OutboxRelayConfig config = OutboxRelayConfig.builder()
                .pollingIntervalMs(100L)
                .batchSize(10)
                .leaseTimeoutMs(30_000L)
                .maxAttempts(3)
                .backoffBaseDelayMs(1_000L)
                .backoffMaxDelayMs(60_000L)
                .strategy(RelayStrategy.LISTEN_NOTIFY)
                .build();

        OutboxRelay relay = new OutboxRelay(config, repository, handlerMap, caps, connectOptions, "relay-it-node");

        // --- Insert one KAFKA outbox entry ---
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination("relay-it-topic")
                .eventType("test.event")
                .payload(new JsonObject().put("k", "v"))
                .build();

        pool.withTransaction(tx -> repository.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx))
                .compose(entryId -> {
                    // --- Deploy the relay verticle ---
                    return vertx.deployVerticle(relay).map(did -> {
                        deploymentId = did;
                        return entryId;
                    });
                })
                .compose(entryId -> {
                    // --- Poll the DB deterministically until state == PUBLISHED ---
                    io.vertx.core.Promise<Void> published = io.vertx.core.Promise.promise();
                    pollUntilPublished(vertx, entryId, 0, published);
                    return published.future().map(ignored -> entryId);
                })
                .compose(entryId -> {
                    // --- Verify delivery ---
                    ctx.verify(() -> {
                        OutboxEnvelope envelope = captured.get();
                        assertNotNull(envelope, "handler must have been called with the delivered envelope");
                        assertEquals("relay-it-topic", envelope.destination(), "destination must match");
                        assertEquals("test.event", envelope.eventType(), "eventType must match");
                    });

                    // --- Undeploy to exercise stop() ---
                    String did = deploymentId;
                    deploymentId = null; // prevent afterEach from double-undeploying
                    return vertx.undeploy(did);
                })
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    // --- Helpers ---

    /**
     * Recursively polls the outbox row for {@code entryId} every 50 ms until {@code state}
     * becomes {@code "PUBLISHED"}, then completes {@code promise}. Fails {@code promise}
     * after ~10 s (200 attempts × 50 ms) to avoid looping past the class-level {@code @Timeout}.
     *
     * @param vertx    the Vert.x instance used to schedule the next poll timer
     * @param entryId  primary key of the outbox row to monitor
     * @param attempts number of attempts made so far
     * @param promise  completed when the row reaches {@code PUBLISHED} state, or failed on timeout
     */
    private void pollUntilPublished(Vertx vertx, long entryId, int attempts, io.vertx.core.Promise<Void> promise) {
        pool.preparedQuery("SELECT state FROM outbox WHERE id = $1")
                .execute(Tuple.of(entryId))
                .onFailure(promise::fail)
                .onSuccess(rows -> {
                    if (rows.iterator().hasNext()) {
                        String state = rows.iterator().next().getString("state");
                        if ("PUBLISHED".equals(state)) {
                            promise.complete();
                            return;
                        }
                    }
                    if (attempts >= 200) {
                        promise.fail("outbox entry " + entryId + " did not reach PUBLISHED state within ~10s");
                        return;
                    }
                    // raw timer: local transient retry backoff (poll-outcome wait)
                    vertx.setTimer(50, id -> pollUntilPublished(vertx, entryId, attempts + 1, promise));
                });
    }

    // --- Inner class: capturing KAFKA handler ---

    /**
     * Test handler for {@link DestinationType#KAFKA} that captures the delivered envelope and
     * returns a successful publish result.
     */
    private static final class CapturingKafkaHandler implements OutboxDestinationHandler {

        private final AtomicReference<OutboxEnvelope> captured;

        /**
         * Constructs the handler.
         *
         * @param captured shared reference that receives the delivered envelope
         */
        CapturingKafkaHandler(AtomicReference<OutboxEnvelope> captured) {
            this.captured = captured;
        }

        @Override
        public DestinationType destinationType() {
            return DestinationType.KAFKA;
        }

        @Override
        public ClaimScope claimScope() {
            return ClaimScope.all();
        }

        /**
         * Stores the delivered envelope and returns a successful publish result.
         *
         * @param envelope the delivery envelope from the relay
         * @return a succeeded future wrapping {@link OutboxPublishResult#success()}
         */
        @Override
        public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
            captured.set(envelope);
            return Future.succeededFuture(OutboxPublishResult.success());
        }
    }
}
