// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxEntryState;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.RelayCapabilities;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgInboxOutboxRepository} against a real PostgreSQL instance.
 *
 * <p>Verifies: inbox deduplication (tryInsert, cross-source independence, cleanup), outbox insert
 * (all fields persisted), claimBatch (capability filtering, available_at/scheduled_at gating,
 * aggregate head-of-line blocking, SKIP LOCKED), state transitions (markPublished, markRetry,
 * markDeadLetter, markUnresolvable), stale lease recovery (reclaimStale, late-completion guard),
 * and retention cleanup (cleanupPublished, cleanupDeadLetter, inbox cleanup).
 * {@link OutboxRecordMapper} field mapping is verified through claimBatch and RETURNING-clause
 * assertions.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgInboxOutboxRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("inbox_outbox_test")
            .withMigration("classpath:db/migration/inbox-outbox");

    static Pool pool;
    static PgInboxOutboxRepository repository;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgInboxOutboxRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE outbox, inbox RESTART IDENTITY").execute().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    /** Builds a basic KAFKA outbox entry targeting the given topic. */
    private static OutboxEntry kafkaEntry(String topic) {
        return kafkaEntry(topic, null, null);
    }

    private static OutboxEntry kafkaEntry(String topic, String aggregateType, String aggregateId) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination(topic)
                .eventType("test.event")
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .payload(new JsonObject().put("key", "value"))
                .build();
    }

    /** Builds a basic SERVICE outbox entry targeting the given stable target id. */
    private static OutboxEntry serviceEntry(String targetId) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(targetId)
                .eventType("test.event")
                .payload(new JsonObject().put("key", "value"))
                .build();
    }

    /** Builds a basic DELAYED_JOB outbox entry targeting the given handler name. */
    private static OutboxEntry delayedJobEntry(String handler) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.DELAYED_JOB)
                .destination(handler)
                .eventType("test.event")
                .payload(new JsonObject().put("key", "value"))
                .build();
    }

    /** Capabilities that support KAFKA and the given SERVICE targets — used for general tests. */
    private static RelayCapabilities allCapabilities(String... serviceTargets) {
        return new RelayCapabilities(Map.of(
                DestinationType.KAFKA,
                ClaimScope.all(),
                DestinationType.SERVICE,
                ClaimScope.destinations(() -> Set.of(serviceTargets))));
    }

    private static RelayCapabilities kafkaOnly() {
        return new RelayCapabilities(Map.of(DestinationType.KAFKA, ClaimScope.all()));
    }

    private static RelayCapabilities noCapabilities() {
        return new RelayCapabilities(Map.of());
    }

    /** Reads a single outbox row's state, attempt, and claimed_by. */
    private Future<io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>> queryOutbox(long id) {
        return pool.preparedQuery("SELECT state, attempt, claimed_by, available_at FROM outbox WHERE id = $1")
                .execute(Tuple.of(id));
    }

    /**
     * Convenience wrapper: inserts an outbox entry with {@link OutboxMetadata#empty()} context and a
     * freshly generated carrier id, matching the pre-migration two-arg call pattern used throughout
     * these tests.
     */
    private Future<Long> insert(OutboxEntry entry, io.vertx.sqlclient.SqlClient tx) {
        return repository.insert(entry, OutboxMetadata.empty(), UUID.randomUUID(), tx);
    }

    // =========================================================================
    // Inbox — tryInsert
    // =========================================================================

    @Test
    @DisplayName("tryInsert returns true for a new (messageId, source) pair")
    void tryInsertNewMessageReturnsTrue(VertxTestContext ctx) {
        String msgId = UUID.randomUUID().toString();
        pool.withTransaction(tx -> repository.tryInsert(msgId, "source-a", tx))
                .onSuccess(inserted -> ctx.verify(() -> {
                    assertTrue(inserted, "first insert must return true");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("tryInsert returns false for a duplicate (messageId, source) pair")
    void tryInsertDuplicateReturnsFalse(VertxTestContext ctx) {
        String msgId = UUID.randomUUID().toString();
        pool.withTransaction(tx -> repository.tryInsert(msgId, "src", tx))
                .compose(v -> pool.withTransaction(tx -> repository.tryInsert(msgId, "src", tx)))
                .onSuccess(inserted -> ctx.verify(() -> {
                    assertFalse(inserted, "second insert for same (messageId, source) must return false");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("tryInsert returns true when same messageId is reused with a different source")
    void tryInsertSameMessageIdDifferentSourceReturnsTrue(VertxTestContext ctx) {
        String msgId = UUID.randomUUID().toString();
        pool.withTransaction(tx -> repository.tryInsert(msgId, "source-x", tx))
                .compose(v -> pool.withTransaction(tx -> repository.tryInsert(msgId, "source-y", tx)))
                .onSuccess(inserted -> ctx.verify(() -> {
                    assertTrue(inserted, "same messageId with different source must return true");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Inbox — cleanup
    // =========================================================================

    @Test
    @DisplayName("inbox cleanup deletes entries older than the retention window")
    void inboxCleanupDeletesOldEntries(VertxTestContext ctx) {
        String msgId = "cleanup-inbox-" + UUID.randomUUID();
        pool.withTransaction(tx -> repository.tryInsert(msgId, "cleanup-src", tx))
                // Age the inserted row by 60 days
                .compose(v -> pool.preparedQuery("UPDATE inbox SET processed_at = NOW() - INTERVAL '60 days'"
                                + " WHERE message_id = $1 AND source = $2")
                        .execute(Tuple.of(msgId, "cleanup-src")))
                .compose(v -> repository.cleanup(30, 100))
                .compose(count -> pool.preparedQuery("SELECT COUNT(*) FROM inbox WHERE message_id = $1 AND source = $2")
                        .execute(Tuple.of(msgId, "cleanup-src"))
                        .map(rs -> rs.iterator().next().getInteger(0)))
                .onSuccess(remaining -> ctx.verify(() -> {
                    assertEquals(0, remaining, "aged entry should be deleted by cleanup");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — insert
    // =========================================================================

    @Test
    @DisplayName("insert returns a positive auto-generated ID")
    void insertReturnsId(VertxTestContext ctx) {
        OutboxEntry entry = kafkaEntry("topic-id-test");
        pool.withTransaction(tx -> insert(entry, tx))
                .onSuccess(id -> ctx.verify(() -> {
                    assertNotNull(id);
                    assertTrue(id > 0, "generated ID must be positive");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("insert persists all fields including headers, aggregateType, aggregateId")
    void insertPersistsAllFields(VertxTestContext ctx) {
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination("orders-topic")
                .eventType("order.placed")
                .aggregateType("Order")
                .aggregateId("order-42")
                .payload(new JsonObject().put("orderId", "o-42"))
                .headers(Map.of("ce-type", "order.placed"))
                .maxAttempts(10)
                .build();

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(id -> pool.preparedQuery("SELECT aggregate_type, aggregate_id, event_type, destination,"
                                + " destination_type, headers, max_attempts"
                                + " FROM outbox WHERE id = $1")
                        .execute(Tuple.of(id))
                        .map(rs -> rs.iterator().next()))
                .onSuccess(row -> ctx.verify(() -> {
                    assertEquals("Order", row.getString("aggregate_type"));
                    assertEquals("order-42", row.getString("aggregate_id"));
                    assertEquals("order.placed", row.getString("event_type"));
                    assertEquals("orders-topic", row.getString("destination"));
                    assertEquals("KAFKA", row.getString("destination_type"));
                    assertEquals("order.placed", row.getJsonObject("headers").getString("ce-type"));
                    assertEquals(10, row.getInteger("max_attempts"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — claimBatch (also exercises OutboxRecordMapper)
    // =========================================================================

    @Test
    @DisplayName("claimBatch claims PENDING entries and maps all OutboxRecord fields correctly")
    void claimBatchClaimsPendingAndMapsFields(VertxTestContext ctx) {
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination("map-test-topic")
                .eventType("test.mapped")
                .aggregateType("Order")
                .aggregateId("agg-map-" + UUID.randomUUID())
                .payload(new JsonObject().put("x", 1))
                .headers(Map.of("h", "v"))
                .maxAttempts(7)
                .build();

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(
                        id -> repository.claimBatch(10, "node-map", kafkaOnly()).map(records -> records.stream()
                                .filter(r -> r.id() == id)
                                .findFirst()
                                .orElse(null)))
                .onSuccess(record -> ctx.verify(() -> {
                    assertNotNull(record, "inserted entry must be claimed");
                    assertEquals(DestinationType.KAFKA, record.destinationType());
                    assertEquals("map-test-topic", record.destination());
                    assertEquals("test.mapped", record.eventType());
                    assertEquals("Order", record.aggregateType());
                    assertEquals(OutboxEntryState.PROCESSING, record.state());
                    assertEquals(0, record.attempt());
                    assertEquals(7, record.maxAttempts());
                    assertEquals("v", record.headers().get("h"));
                    assertEquals("node-map", record.claimedBy());
                    assertNotNull(record.claimedAt());
                    assertNotNull(record.createdAt());
                    assertNotNull(record.updatedAt());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("carrier_id round-trips through insert -> claimBatch: the claimed row's carrierId equals the"
            + " producer-generated UUID (PRD identity-002 F3b, per-row carrier binding)")
    void carrierIdRoundTripsThroughInsertAndClaim(VertxTestContext ctx) {
        UUID carrierId = UUID.randomUUID();
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination("carrier-map-test-topic")
                .eventType("test.carrier")
                .payload(new JsonObject().put("x", 1))
                .build();

        pool.withTransaction(tx -> repository.insert(entry, OutboxMetadata.empty(), carrierId, tx))
                .compose(id -> repository
                        .claimBatch(10, "node-carrier", kafkaOnly())
                        .map(records -> records.stream()
                                .filter(r -> r.id() == id)
                                .findFirst()
                                .orElse(null)))
                .onSuccess(record -> ctx.verify(() -> {
                    assertNotNull(record, "inserted entry must be claimed");
                    assertEquals(
                            carrierId,
                            record.carrierId(),
                            "claimed record's carrierId must equal the producer-generated carrier id, read back"
                                    + " from the first-class carrier_id column");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch does not claim KAFKA entries when kafkaEnabled=false")
    void claimBatchSkipsKafkaWhenDisabled(VertxTestContext ctx) {
        String topic = "kafka-cap-" + UUID.randomUUID();
        pool.withTransaction(tx -> insert(kafkaEntry(topic), tx))
                .compose(id -> repository
                        .claimBatch(10, "node-x", noCapabilities())
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertFalse(claimed, "KAFKA entry must not be claimed when kafkaEnabled=false");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch does not claim SERVICE entries whose target is not in serviceTargetIds")
    void claimBatchSkipsServiceNotInTargetIds(VertxTestContext ctx) {
        String targetId = "svc.target." + UUID.randomUUID();
        pool.withTransaction(tx -> insert(serviceEntry(targetId), tx))
                .compose(id -> repository
                        .claimBatch(
                                10,
                                "node-x",
                                new RelayCapabilities(Map.of(
                                        DestinationType.SERVICE,
                                        ClaimScope.destinations(() -> Set.of("other.target")))))
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertFalse(claimed, "SERVICE entry must not be claimed when target not in serviceTargetIds");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch claims SERVICE entries when target is in serviceTargetIds")
    void claimBatchClaimsServiceInTargetIds(VertxTestContext ctx) {
        String targetId = "svc.target." + UUID.randomUUID();
        pool.withTransaction(tx -> insert(serviceEntry(targetId), tx))
                .compose(id -> repository
                        .claimBatch(
                                10,
                                "node-x",
                                new RelayCapabilities(Map.of(
                                        DestinationType.SERVICE, ClaimScope.destinations(() -> Set.of(targetId)))))
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertTrue(claimed, "SERVICE entry must be claimed when target is in serviceTargetIds");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch claims DELAYED_JOB entries when target is in delayedJobTargetIds")
    void claimBatchClaimsDelayedJobInTargetIds(VertxTestContext ctx) {
        String handler = "my-handler-" + UUID.randomUUID();
        pool.withTransaction(tx -> insert(delayedJobEntry(handler), tx))
                .compose(id -> repository
                        .claimBatch(
                                10,
                                "node-x",
                                new RelayCapabilities(Map.of(
                                        DestinationType.DELAYED_JOB, ClaimScope.destinations(() -> Set.of(handler)))))
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertTrue(claimed, "DELAYED_JOB entry must be claimed when handler is in delayedJobTargetIds");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch does not claim entries whose available_at is in the future")
    void claimBatchSkipsFutureAvailableAt(VertxTestContext ctx) {
        String topic = "avail-" + UUID.randomUUID();
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination(topic)
                .eventType("test.event")
                .payload(new JsonObject())
                .availableAt(Instant.now().plusSeconds(300))
                .build();

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(id -> repository.claimBatch(10, "node-x", kafkaOnly()).map(records -> records.stream()
                        .anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertFalse(claimed, "Entry with future available_at must not be claimed");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch does not claim entries whose scheduled_at is in the future")
    void claimBatchSkipsFutureScheduledAt(VertxTestContext ctx) {
        String topic = "sched-" + UUID.randomUUID();
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.KAFKA)
                .destination(topic)
                .eventType("test.event")
                .payload(new JsonObject())
                .scheduledAt(Instant.now().plusSeconds(300))
                .build();

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(id -> repository.claimBatch(10, "node-x", kafkaOnly()).map(records -> records.stream()
                        .anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertFalse(claimed, "Entry with future scheduled_at must not be claimed");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — aggregate head-of-line blocking
    // =========================================================================

    @Test
    @DisplayName("claimBatch with shared aggregate_id: only the oldest entry (MIN id) is claimed")
    void claimBatchAggregateHeadOfLineBlocksYoungerSibling(VertxTestContext ctx) {
        String aggId = "agg-hol-" + UUID.randomUUID();
        String topic = "hol-topic-" + UUID.randomUUID();
        OutboxEntry first = kafkaEntry(topic, "Order", aggId);
        OutboxEntry second = kafkaEntry(topic, "Order", aggId);

        pool.withTransaction(tx -> insert(first, tx))
                .compose(id1 -> pool.withTransaction(tx -> insert(second, tx)).compose(id2 -> repository
                        .claimBatch(10, "node-hol", kafkaOnly())
                        .map(records -> {
                            boolean firstClaimed = records.stream().anyMatch(r -> r.id() == id1);
                            boolean secondClaimed = records.stream().anyMatch(r -> r.id() == id2);
                            return new boolean[] {firstClaimed, secondClaimed};
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue(result[0], "First (older) entry must be claimed");
                    assertFalse(result[1], "Second (younger) entry must be blocked by head-of-line");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch unblocks sibling after PUBLISHED: younger entry becomes claimable")
    void claimBatchUnblocksAfterPublished(VertxTestContext ctx) {
        String aggId = "agg-pub-" + UUID.randomUUID();
        String topic = "pub-topic-" + UUID.randomUUID();
        String nodeId = "node-pub-" + UUID.randomUUID().toString().substring(0, 8);

        pool.withTransaction(tx -> insert(kafkaEntry(topic, "Order", aggId), tx))
                .compose(id1 -> pool.withTransaction(tx -> insert(kafkaEntry(topic, "Order", aggId), tx))
                        .compose(id2 ->
                                // Claim id1
                                repository
                                        .claimBatch(1, nodeId, kafkaOnly())
                                        // Mark id1 as PUBLISHED
                                        .compose(v -> repository.markPublished(id1, nodeId))
                                        // Now claim again — id2 should be available
                                        .compose(v -> repository.claimBatch(1, nodeId, kafkaOnly()))
                                        .map(records -> records.stream().anyMatch(r -> r.id() == id2))))
                .onSuccess(id2Claimed -> ctx.verify(() -> {
                    assertTrue(id2Claimed, "Younger sibling must be claimable after older sibling is PUBLISHED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch unblocks sibling after DEAD_LETTER: younger entry becomes claimable")
    void claimBatchUnblocksAfterDeadLetter(VertxTestContext ctx) {
        String aggId = "agg-dl-" + UUID.randomUUID();
        String topic = "dl-topic-" + UUID.randomUUID();
        String nodeId = "node-dl-" + UUID.randomUUID().toString().substring(0, 8);

        pool.withTransaction(tx -> insert(kafkaEntry(topic, "Order", aggId), tx))
                .compose(id1 -> pool.withTransaction(tx -> insert(kafkaEntry(topic, "Order", aggId), tx))
                        .compose(id2 -> repository
                                .claimBatch(1, nodeId, kafkaOnly())
                                .compose(v -> repository.markDeadLetter(id1, nodeId, "error", "ERR"))
                                .compose(v -> repository.claimBatch(1, nodeId, kafkaOnly()))
                                .map(records -> records.stream().anyMatch(r -> r.id() == id2))))
                .onSuccess(id2Claimed -> ctx.verify(() -> {
                    assertTrue(id2Claimed, "Younger sibling must be claimable after older sibling is DEAD_LETTER");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("null aggregate_id entries are independent: not blocked by each other")
    void claimBatchNullAggregateIdIsIndependent(VertxTestContext ctx) {
        String topic = "null-agg-" + UUID.randomUUID();
        // Two entries with null aggregateId — both should be claimable
        pool.withTransaction(tx -> insert(kafkaEntry(topic), tx))
                .compose(id1 -> pool.withTransaction(tx -> insert(kafkaEntry(topic), tx))
                        .compose(id2 -> repository
                                .claimBatch(10, "node-null", kafkaOnly())
                                .map(records -> {
                                    boolean firstClaimed = records.stream().anyMatch(r -> r.id() == id1);
                                    boolean secondClaimed = records.stream().anyMatch(r -> r.id() == id2);
                                    return firstClaimed && secondClaimed;
                                })))
                .onSuccess(bothClaimed -> ctx.verify(() -> {
                    assertTrue(bothClaimed, "null aggregate_id entries must not block each other");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — claimBatch generalized eligibility (multi-type, validation, R-HOL)
    // =========================================================================

    @Test
    @DisplayName("claimBatch mixed types: KAFKA+SERVICE claimed, DELAYED_JOB without scope skipped")
    void claimBatchMixedTypeParityKafkaAndServiceClaimedDelayedJobSkipped(VertxTestContext ctx) {
        String topic = "mix-kafka-" + UUID.randomUUID();
        String svcTarget = "mix-svc-" + UUID.randomUUID();
        String djHandler = "mix-dj-" + UUID.randomUUID();

        pool.withTransaction(tx -> insert(kafkaEntry(topic), tx))
                .compose(kafkaId -> pool.withTransaction(tx -> insert(serviceEntry(svcTarget), tx))
                        .compose(svcId -> pool.withTransaction(tx -> insert(delayedJobEntry(djHandler), tx))
                                .compose(djId -> {
                                    RelayCapabilities caps = new RelayCapabilities(Map.of(
                                            DestinationType.KAFKA,
                                            ClaimScope.all(),
                                            DestinationType.SERVICE,
                                            ClaimScope.destinations(() -> Set.of(svcTarget))));
                                    return repository
                                            .claimBatch(10, "node-mix", caps)
                                            .map(records -> new boolean[] {
                                                records.stream().anyMatch(r -> r.id() == kafkaId),
                                                records.stream().anyMatch(r -> r.id() == svcId),
                                                records.stream().anyMatch(r -> r.id() == djId)
                                            });
                                })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue(result[0], "KAFKA row must be claimed");
                    assertTrue(result[1], "SERVICE row with matching target must be claimed");
                    assertFalse(result[2], "DELAYED_JOB row must not be claimed: no scope registered for it");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch empty destinations scope claims nothing for that type")
    void claimBatchEmptyDestinationsScopeClaimsNothing(VertxTestContext ctx) {
        String svcTarget = "empty-scope-svc-" + UUID.randomUUID();
        pool.withTransaction(tx -> insert(serviceEntry(svcTarget), tx))
                .compose(id -> repository
                        .claimBatch(
                                10,
                                "node-empty",
                                new RelayCapabilities(
                                        Map.of(DestinationType.SERVICE, ClaimScope.destinations(Set::of))))
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertFalse(claimed, "Empty destinations scope must claim nothing");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch supplier throws → failed future with ClaimScopeException naming type, not secret")
    void claimBatchSupplierThrowsFailsFutureWithClaimScopeException(VertxTestContext ctx) {
        String svcTarget = "throw-svc-" + UUID.randomUUID();
        pool.withTransaction(tx -> insert(serviceEntry(svcTarget), tx))
                .compose(id -> repository.claimBatch(
                        10,
                        "node-throw",
                        new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> {
                            throw new RuntimeException("boom-secret");
                        })))))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "Future must fail when supplier throws");
                    Throwable cause = ar.cause();
                    assertInstanceOf(ClaimScopeException.class, cause, "Cause must be ClaimScopeException");
                    String msg = cause.getMessage();
                    assertTrue(msg.contains("SERVICE"), "Message must name the destination type");
                    assertFalse(msg.contains("boom-secret"), "Message must not contain the raw exception message");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimBatch with a hostile target set (throws on iteration) fails closed, not synchronously")
    void claimBatchHostileTargetSetFailsClosed(VertxTestContext ctx) {
        // A Set whose iterator() throws models a misbehaving ClaimScope.destinations supplier RESULT
        // (as opposed to the supplier itself throwing). It must still fail the claim cycle as a
        // sanitized failed future — never escape claimBatch as a raw synchronous exception, which would
        // break the relay poll loop's failed-Future contract.
        Set<String> hostile = new java.util.AbstractSet<>() {
            @Override
            public java.util.Iterator<String> iterator() {
                throw new RuntimeException("boom-hostile-target");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        Future<List<OutboxRecord>> result = repository.claimBatch(
                10,
                "node-hostile",
                new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> hostile))));
        result.onComplete(ar -> ctx.verify(() -> {
            assertTrue(ar.failed(), "hostile target set must fail the claim cycle");
            assertInstanceOf(ClaimScopeException.class, ar.cause(), "Cause must be ClaimScopeException");
            assertFalse(
                    ar.cause().getMessage().contains("boom-hostile-target"),
                    "Message must not leak the supplier-result exception detail");
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("claimBatch supplier returns null → failed future with ClaimScopeException naming SERVICE")
    void claimBatchSupplierReturnsNullFailsFuture(VertxTestContext ctx) {
        repository
                .claimBatch(
                        10,
                        "node-null",
                        new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> null))))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "Future must fail when supplier returns null");
                    assertInstanceOf(ClaimScopeException.class, ar.cause(), "Cause must be ClaimScopeException");
                    assertTrue(ar.cause().getMessage().contains("SERVICE"), "Message must name the destination type");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimBatch blank element in supplier set → failed future with ClaimScopeException")
    void claimBatchBlankElementInSupplierSetFailsFuture(VertxTestContext ctx) {
        repository
                .claimBatch(
                        10,
                        "node-blank",
                        new RelayCapabilities(
                                Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> Set.of(" ")))))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "Future must fail when supplier set contains a blank element");
                    assertInstanceOf(ClaimScopeException.class, ar.cause(), "Cause must be ClaimScopeException");
                    assertTrue(ar.cause().getMessage().contains("SERVICE"), "Message must name the destination type");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimBatch oversized element → failed future, message does not echo the value")
    void claimBatchOversizedElementFailsFutureWithoutEchoing(VertxTestContext ctx) {
        String secret = "SUPER-SECRET-" + "x".repeat(300); // length > 255
        repository
                .claimBatch(
                        10,
                        "node-oversize",
                        new RelayCapabilities(
                                Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> Set.of(secret)))))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "Future must fail for oversized element");
                    assertInstanceOf(ClaimScopeException.class, ar.cause(), "Cause must be ClaimScopeException");
                    String msg = ar.cause().getMessage();
                    assertTrue(msg.contains("SERVICE"), "Message must name the destination type");
                    assertFalse(msg.contains("SUPER-SECRET"), "Message must not echo the oversized value");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimBatch cardinality cap exceeded → failed future with ClaimScopeException")
    void claimBatchCardinalityCapExceededFailsFuture(VertxTestContext ctx) {
        Set<String> bigSet = IntStream.rangeClosed(0, 10_000)
                .mapToObj(i -> "t" + i)
                .collect(Collectors.toUnmodifiableSet()); // 10_001 elements
        repository
                .claimBatch(
                        10,
                        "node-bigset",
                        new RelayCapabilities(Map.of(DestinationType.SERVICE, ClaimScope.destinations(() -> bigSet))))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "Future must fail when destination set exceeds cardinality cap");
                    assertInstanceOf(ClaimScopeException.class, ar.cause(), "Cause must be ClaimScopeException");
                    assertTrue(ar.cause().getMessage().contains("SERVICE"), "Message must name the destination type");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("claimBatch injection-shaped destination is bound as parameter, not interpolated")
    void claimBatchInjectionShapedDestinationBoundAsParameter(VertxTestContext ctx) {
        // Destination value contains SQL injection syntax; it must be safely bound via ANY($n).
        String injectionValue = "x'; DROP TABLE outbox; --";
        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(injectionValue)
                .eventType("test.event")
                .payload(new JsonObject().put("key", "value"))
                .build();

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(id -> repository
                        .claimBatch(
                                10,
                                "node-inject",
                                new RelayCapabilities(Map.of(
                                        DestinationType.SERVICE,
                                        ClaimScope.destinations(() -> Set.of(injectionValue)))))
                        .map(records -> records.stream().anyMatch(r -> r.id() == id)))
                .compose(claimed ->
                        // Verify the outbox table is still intact after the claim attempt.
                        pool.query("SELECT 1 FROM outbox LIMIT 1").execute().map(ignored -> claimed))
                .onSuccess(claimed -> ctx.verify(() -> {
                    assertTrue(claimed, "Row with injection-shaped destination must be claimed via safe binding");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("R-HOL: unclaimable-type head blocks younger claimable sibling of same aggregate")
    void claimBatchUnclaimableTypeHeadBlocksYoungerClaimableSibling(VertxTestContext ctx) {
        // The older/head row is DELAYED_JOB — no scope is registered for it.
        // The younger row is KAFKA — claimable. But because the head is still PENDING,
        // the KAFKA sibling is blocked by head-of-line ordering (MIN(id) still PENDING).
        // This is the documented intended behavior: an unregistered/unscoped destination type
        // at an aggregate head blocks the chain until its handler/scope is registered.
        String aggId = "agg-rhol-" + UUID.randomUUID();
        String djHandler = "rhol-dj-handler";
        String topic = "rhol-topic-" + UUID.randomUUID();

        OutboxEntry headEntry = OutboxEntry.builder()
                .destinationType(DestinationType.DELAYED_JOB)
                .destination(djHandler)
                .eventType("test.event")
                .aggregateType("Order")
                .aggregateId(aggId)
                .payload(new JsonObject().put("key", "value"))
                .build();
        OutboxEntry siblingEntry = kafkaEntry(topic, "Order", aggId);

        pool.withTransaction(tx -> insert(headEntry, tx))
                .compose(headId -> pool.withTransaction(tx -> insert(siblingEntry, tx))
                        .compose(siblingId -> {
                            // Claim with KAFKA-only — DELAYED_JOB head has no scope.
                            RelayCapabilities kafkaOnlyCaps =
                                    new RelayCapabilities(Map.of(DestinationType.KAFKA, ClaimScope.all()));
                            return repository
                                    .claimBatch(10, "node-rhol", kafkaOnlyCaps)
                                    .map(records -> new boolean[] {
                                        records.stream().anyMatch(r -> r.id() == headId),
                                        records.stream().anyMatch(r -> r.id() == siblingId)
                                    });
                        }))
                .onSuccess(result -> ctx.verify(() -> {
                    assertFalse(result[0], "DELAYED_JOB head must not be claimed: no scope for it");
                    assertFalse(result[1], "KAFKA sibling must be blocked by the PENDING DELAYED_JOB head-of-line");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimBatch concurrent calls do not claim the same entry (SKIP LOCKED)")
    void claimBatchSkipLockedPreventsDoubleClaim(VertxTestContext ctx) {
        String topic = "skip-locked-" + UUID.randomUUID();
        pool.withTransaction(tx -> insert(kafkaEntry(topic), tx))
                .compose(id -> Future.all(
                                repository.claimBatch(10, "node-a", kafkaOnly()),
                                repository.claimBatch(10, "node-b", kafkaOnly()))
                        .map(results -> {
                            List<OutboxRecord> a = results.resultAt(0);
                            List<OutboxRecord> b = results.resultAt(1);
                            long claimedTwice = a.stream()
                                            .filter(r -> r.id() == id)
                                            .count()
                                    + b.stream().filter(r -> r.id() == id).count();
                            return claimedTwice;
                        }))
                .onSuccess(count -> ctx.verify(() -> {
                    assertTrue(count <= 1, "Same entry must not be claimed by two concurrent callers");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — markPublished
    // =========================================================================

    @Test
    @DisplayName("markPublished sets state to PUBLISHED and returns true")
    void markPublishedSetsPublishedState(VertxTestContext ctx) {
        String nodeId = "node-pub-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-pub"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markPublished(id, nodeId))
                        .compose(ok -> queryOutbox(id).map(rs -> {
                            var row = rs.iterator().next();
                            return Map.of("ok", ok, "state", row.getString("state"));
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue((Boolean) result.get("ok"), "markPublished must return true");
                    assertEquals("PUBLISHED", result.get("state"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markPublished returns false when claimedBy does not match")
    void markPublishedWrongClaimedByReturnsFalse(VertxTestContext ctx) {
        pool.withTransaction(tx -> insert(kafkaEntry("topic-pub-guard"), tx))
                .compose(id -> repository
                        .claimBatch(1, "right-node", kafkaOnly())
                        .compose(v -> repository.markPublished(id, "wrong-node")))
                .onSuccess(ok -> ctx.verify(() -> {
                    assertFalse(ok, "markPublished must return false when claimedBy does not match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — markRetry
    // =========================================================================

    @Test
    @DisplayName("markRetry sets state to PENDING, increments attempt, and sets available_at")
    void markRetrySetsRetryState(VertxTestContext ctx) {
        String nodeId = "node-retry-" + UUID.randomUUID().toString().substring(0, 8);
        Instant retryAt = Instant.now().plusSeconds(30);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-retry"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markRetry(id, nodeId, 1, retryAt, "err msg", "ERR_TYPE"))
                        .compose(ok -> queryOutbox(id).map(rs -> {
                            var row = rs.iterator().next();
                            return Map.of(
                                    "ok", ok,
                                    "state", row.getString("state"),
                                    "attempt", row.getInteger("attempt"),
                                    "claimedBy", String.valueOf(row.getString("claimed_by")));
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue((Boolean) result.get("ok"), "markRetry must return true");
                    assertEquals("PENDING", result.get("state"));
                    assertEquals(1, result.get("attempt"));
                    assertEquals("null", result.get("claimedBy"), "claimed_by must be cleared after retry");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markRetry returns false when claimedBy does not match")
    void markRetryWrongClaimedByReturnsFalse(VertxTestContext ctx) {
        pool.withTransaction(tx -> insert(kafkaEntry("topic-retry-guard"), tx))
                .compose(id -> repository
                        .claimBatch(1, "right-node", kafkaOnly())
                        .compose(v -> repository.markRetry(
                                id, "wrong-node", 1, Instant.now().plusSeconds(5), "err", "ERR")))
                .onSuccess(ok -> ctx.verify(() -> {
                    assertFalse(ok, "markRetry must return false when claimedBy does not match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — markDeadLetter
    // =========================================================================

    @Test
    @DisplayName("markDeadLetter sets state to DEAD_LETTER and returns true")
    void markDeadLetterSetsDeadLetterState(VertxTestContext ctx) {
        String nodeId = "node-dl-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-dl"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markDeadLetter(id, nodeId, "fatal error", "FATAL"))
                        .compose(ok -> queryOutbox(id).map(rs -> {
                            var row = rs.iterator().next();
                            return Map.of("ok", ok, "state", row.getString("state"));
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue((Boolean) result.get("ok"), "markDeadLetter must return true");
                    assertEquals("DEAD_LETTER", result.get("state"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markDeadLetter returns false when claimedBy does not match")
    void markDeadLetterWrongClaimedByReturnsFalse(VertxTestContext ctx) {
        pool.withTransaction(tx -> insert(kafkaEntry("topic-dl-guard"), tx))
                .compose(id -> repository
                        .claimBatch(1, "right-node", kafkaOnly())
                        .compose(v -> repository.markDeadLetter(id, "wrong-node", "err", "ERR")))
                .onSuccess(ok -> ctx.verify(() -> {
                    assertFalse(ok, "markDeadLetter must return false when claimedBy does not match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — markUnresolvable
    // =========================================================================

    @Test
    @DisplayName("markUnresolvable returns entry to PENDING with delay and does NOT increment attempt")
    void markUnresolvableReturnsToPendingWithoutAttemptIncrement(VertxTestContext ctx) {
        String nodeId = "node-unr-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-unr"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markUnresolvable(id, nodeId, Duration.ofSeconds(60)))
                        .compose(ok -> queryOutbox(id).map(rs -> {
                            var row = rs.iterator().next();
                            return Map.of(
                                    "ok", ok,
                                    "state", row.getString("state"),
                                    "attempt", row.getInteger("attempt"),
                                    "claimedBy", String.valueOf(row.getString("claimed_by")));
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue((Boolean) result.get("ok"), "markUnresolvable must return true");
                    assertEquals("PENDING", result.get("state"), "state must return to PENDING");
                    assertEquals(0, result.get("attempt"), "attempt must NOT be incremented by markUnresolvable");
                    assertEquals("null", result.get("claimedBy"), "claimed_by must be cleared");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markUnresolvable returns false when claimedBy does not match")
    void markUnresolvableWrongClaimedByReturnsFalse(VertxTestContext ctx) {
        pool.withTransaction(tx -> insert(kafkaEntry("topic-unr-guard"), tx))
                .compose(id -> repository
                        .claimBatch(1, "right-node", kafkaOnly())
                        .compose(v -> repository.markUnresolvable(id, "wrong-node", Duration.ofSeconds(5))))
                .onSuccess(ok -> ctx.verify(() -> {
                    assertFalse(ok, "markUnresolvable must return false when claimedBy does not match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — reclaimStale
    // =========================================================================

    @Test
    @DisplayName("reclaimStale resets PROCESSING entries whose claimed_at exceeds lease timeout")
    void reclaimStaleResetsExpiredEntries(VertxTestContext ctx) {
        String nodeId = "node-stale-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-stale"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        // Age the claimed_at so it appears stale (2 minutes ago)
                        .compose(v -> pool.preparedQuery(
                                        "UPDATE outbox SET claimed_at = NOW() - INTERVAL '2 minutes' WHERE id = $1")
                                .execute(Tuple.of(id)))
                        .compose(v -> repository.reclaimStale(Duration.ofMinutes(1)))
                        .compose(v -> queryOutbox(id).map(rs -> {
                            var row = rs.iterator().next();
                            return Map.of(
                                    "state", row.getString("state"),
                                    "claimedBy", String.valueOf(row.getString("claimed_by")));
                        })))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals("PENDING", result.get("state"), "stale entry must be reset to PENDING");
                    assertEquals("null", result.get("claimedBy"), "claimed_by must be cleared after stale reclaim");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("reclaimStale does not affect PROCESSING entries within the lease window")
    void reclaimStaleDoesNotAffectFreshEntries(VertxTestContext ctx) {
        String nodeId = "node-fresh-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-fresh"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        // Reclaim with a very long lease — current entry should NOT be reclaimed
                        .compose(v -> repository.reclaimStale(Duration.ofHours(24)))
                        .compose(v ->
                                queryOutbox(id).map(rs -> rs.iterator().next().getString("state"))))
                .onSuccess(state -> ctx.verify(() -> {
                    assertEquals("PROCESSING", state, "recently claimed entry must not be reclaimed");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("late completion after stale reclaim: markPublished returns false for old claimedBy")
    void lateCompletionAfterStaleReclaimReturnsFalse(VertxTestContext ctx) {
        String oldNode = "node-old-" + UUID.randomUUID().toString().substring(0, 8);
        String newNode = "node-new-" + UUID.randomUUID().toString().substring(0, 8);

        pool.withTransaction(tx -> insert(kafkaEntry("topic-late"), tx))
                .compose(id ->
                        // Step 1: node-old claims the entry
                        repository
                                .claimBatch(1, oldNode, kafkaOnly())
                                // Step 2: age claimed_at so stale reclaim fires
                                .compose(v -> pool.preparedQuery(
                                                "UPDATE outbox SET claimed_at = NOW() - INTERVAL '2 minutes'"
                                                        + " WHERE id = $1")
                                        .execute(Tuple.of(id)))
                                // Step 3: stale reclaim resets the entry to PENDING
                                .compose(v -> repository.reclaimStale(Duration.ofMinutes(1)))
                                // Step 4: node-new claims the entry
                                .compose(v -> repository.claimBatch(1, newNode, kafkaOnly()))
                                // Step 5: node-old (late) tries to mark as published
                                .compose(v -> repository.markPublished(id, oldNode)))
                .onSuccess(ok -> ctx.verify(() -> {
                    assertFalse(ok, "Late completion by old node must return false after stale reclaim");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — cleanup
    // =========================================================================

    @Test
    @DisplayName("cleanupPublished deletes PUBLISHED entries older than the retention window")
    void cleanupPublishedDeletesOldEntries(VertxTestContext ctx) {
        String nodeId = "node-cpub-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-cpub"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markPublished(id, nodeId))
                        // Age the published_at so it's beyond the retention window
                        .compose(v -> pool.preparedQuery(
                                        "UPDATE outbox SET published_at = NOW() - INTERVAL '10 days' WHERE id = $1")
                                .execute(Tuple.of(id)))
                        .compose(v -> repository.cleanupPublished(7, 100))
                        .compose(count -> pool.preparedQuery("SELECT COUNT(*) FROM outbox WHERE id = $1")
                                .execute(Tuple.of(id))
                                .map(rs -> Map.of("count", rs.iterator().next().getInteger(0), "deleted", count))))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(0, result.get("count"), "Old PUBLISHED entry should be deleted");
                    assertTrue((Integer) result.get("deleted") > 0, "cleanupPublished must report deleted count");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Outbox — synthetic open DestinationType end-to-end (AC1)
    // =========================================================================

    @Test
    @DisplayName(
            "AC1: a synthetic DestinationType.of(\"synthetic\") is claimed and delivered with no core/postgresql edit")
    void syntheticDestinationTypeIsClaimedAndDelivered(VertxTestContext ctx) {
        // Synthetic handler — not one of the built-in constants
        final boolean[] published = {false};
        OutboxDestinationHandler syntheticHandler = new OutboxDestinationHandler() {
            @Override
            public DestinationType destinationType() {
                return DestinationType.of("synthetic");
            }

            @Override
            public ClaimScope claimScope() {
                return ClaimScope.all();
            }

            @Override
            public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
                published[0] = true;
                return Future.succeededFuture(OutboxPublishResult.success());
            }
        };

        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.of("synthetic"))
                .destination("synthetic-target")
                .eventType("test.event")
                .payload(new JsonObject().put("key", "value"))
                .build();

        // Derive capabilities via the production path
        RelayCapabilities caps = OutboxRelay.deriveCapabilities(OutboxRelay.buildHandlerMap(Set.of(syntheticHandler)));

        pool.withTransaction(tx -> insert(entry, tx))
                .compose(id -> repository.claimBatch(10, "node-ac1", caps).compose(records -> {
                    // Assert the inserted row is among the claimed records
                    OutboxRecord claimed = records.stream()
                            .filter(r -> r.id() == id)
                            .findFirst()
                            .orElse(null);
                    ctx.verify(() -> assertNotNull(claimed, "synthetic row must be claimed"));

                    // Prove routing: the handler map resolves to our synthetic handler
                    Map<DestinationType, OutboxDestinationHandler> handlerMap =
                            OutboxRelay.buildHandlerMap(Set.of(syntheticHandler));
                    OutboxDestinationHandler resolved = handlerMap.get(claimed.destinationType());
                    ctx.verify(() -> assertSame(
                            syntheticHandler, resolved, "handler map must resolve to the synthetic handler"));

                    // Prove delivery: call publish and assert it succeeds
                    OutboxEnvelope envelope = new OutboxEnvelope(
                            claimed.id(),
                            claimed.aggregateType(),
                            claimed.aggregateId(),
                            claimed.eventType(),
                            claimed.destination(),
                            claimed.payload(),
                            claimed.headers(),
                            claimed.metadata(),
                            claimed.scheduledAt(),
                            claimed.attempt(),
                            claimed.createdAt());
                    return resolved.publish(envelope)
                            .<Boolean>compose(result -> {
                                ctx.verify(() -> {
                                    assertInstanceOf(OutboxPublishResult.Success.class, result, "publish must succeed");
                                    assertTrue(published[0], "publish() must have been called");
                                });
                                return repository.markPublished(id, "node-ac1");
                            })
                            .compose(ok -> {
                                ctx.verify(() -> assertTrue(ok, "markPublished must return true"));
                                return queryOutbox(id)
                                        .map(rs -> rs.iterator().next().getString("state"));
                            })
                            .map(state -> {
                                ctx.verify(() -> assertEquals("PUBLISHED", state, "row state must be PUBLISHED"));
                                return state;
                            });
                }))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("cleanupDeadLetter deletes DEAD_LETTER entries older than the retention window")
    void cleanupDeadLetterDeletesOldEntries(VertxTestContext ctx) {
        String nodeId = "node-cdl-" + UUID.randomUUID().toString().substring(0, 8);
        pool.withTransaction(tx -> insert(kafkaEntry("topic-cdl"), tx))
                .compose(id -> repository
                        .claimBatch(1, nodeId, kafkaOnly())
                        .compose(v -> repository.markDeadLetter(id, nodeId, "err", "ERR"))
                        // Age the updated_at beyond the retention window
                        .compose(v -> pool.preparedQuery(
                                        "UPDATE outbox SET updated_at = NOW() - INTERVAL '40 days' WHERE id = $1")
                                .execute(Tuple.of(id)))
                        .compose(v -> repository.cleanupDeadLetter(30, 100))
                        .compose(count -> pool.preparedQuery("SELECT COUNT(*) FROM outbox WHERE id = $1")
                                .execute(Tuple.of(id))
                                .map(rs -> Map.of("count", rs.iterator().next().getInteger(0), "deleted", count))))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(0, result.get("count"), "Old DEAD_LETTER entry should be deleted");
                    assertTrue((Integer) result.get("deleted") > 0, "cleanupDeadLetter must report deleted count");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
