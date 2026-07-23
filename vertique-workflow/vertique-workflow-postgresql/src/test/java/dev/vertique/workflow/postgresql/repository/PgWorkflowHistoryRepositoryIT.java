// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link PgWorkflowHistoryRepository} against a real PostgreSQL instance.
 *
 * <p>Verifies: append + listByInstance returns entries ordered by sequence ASC, nextSequence is
 * monotonic and gap-free per instance, and sequences are independent across instances.
 *
 * <p>All repository method bodies are stubs in this commit boundary; every test is expected to
 * fail with {@link UnsupportedOperationException} (red phase).
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowHistoryRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_history")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository instanceRepository;
    static PgWorkflowHistoryRepository historyRepository;

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
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        instanceRepository = new PgWorkflowInstanceRepository(pool, mapper);
        historyRepository = new PgWorkflowHistoryRepository(pool, mapper);
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    /** Creates and inserts a minimal workflow instance, returning its id. */
    private Future<WorkflowInstanceId> insertInstance() {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
        return pool.withTransaction(tx -> instanceRepository.insert(inst, tx)).map(v -> id);
    }

    /** Builds a history entry with the given type and sequence. */
    private static WorkflowHistoryEntry entry(WorkflowInstanceId instanceId, long sequence, WorkflowEntryType type) {
        return new WorkflowHistoryEntry(instanceId, sequence, type, "{}", Instant.now());
    }

    // =========================================================================
    // append + listByInstance
    // =========================================================================

    @Test
    @DisplayName("append entries are retrievable: append does not throw on valid input")
    void appendDoesNotThrowOnValidInput(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceId -> pool.withTransaction(
                        tx -> historyRepository.append(entry(instanceId, 1L, WorkflowEntryType.START), tx)))
                .onSuccess(v -> ctx.verify(() -> {
                    // reaching here means append succeeded without exception
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("append + listByInstance returns entries in correct sequence order")
    void listByInstanceReturnsEntriesInSequenceOrder(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceId -> pool.withTransaction(tx -> historyRepository
                                .append(entry(instanceId, 1L, WorkflowEntryType.START), tx)
                                .compose(v -> historyRepository.append(
                                        entry(instanceId, 2L, WorkflowEntryType.SIDE_EFFECT_RECORDED), tx))
                                .compose(v -> historyRepository.append(
                                        entry(instanceId, 3L, WorkflowEntryType.COMPLETED), tx)))
                        .compose(v -> historyRepository.listByInstance(instanceId))
                        .onSuccess(entries -> ctx.verify(() -> {
                            assertNotNull(entries);
                            assertEquals(3, entries.size(), "must return all 3 appended entries");
                            assertEquals(1L, entries.get(0).sequence());
                            assertEquals(WorkflowEntryType.START, entries.get(0).entryType());
                            assertEquals(2L, entries.get(1).sequence());
                            assertEquals(
                                    WorkflowEntryType.SIDE_EFFECT_RECORDED,
                                    entries.get(1).entryType());
                            assertEquals(3L, entries.get(2).sequence());
                            assertEquals(
                                    WorkflowEntryType.COMPLETED, entries.get(2).entryType());
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("listByInstance returns empty list for an instance with no history")
    void listByInstanceReturnsEmptyForNoHistory(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceId -> historyRepository.listByInstance(instanceId))
                .onSuccess(entries -> ctx.verify(() -> {
                    assertNotNull(entries);
                    assertTrue(entries.isEmpty(), "listByInstance must return empty for an instance with no history");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // nextSequence — monotonic and gap-free
    // =========================================================================

    @Test
    @DisplayName("nextSequence returns 1 for an instance with no history")
    void nextSequenceReturnsOneForFreshInstance(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceId -> pool.withTransaction(tx -> historyRepository.nextSequence(instanceId, tx)))
                .onSuccess(seq -> ctx.verify(() -> {
                    assertEquals(1L, seq, "nextSequence must return 1 for a fresh instance");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("nextSequence is monotonic: returns 2 after one entry, 3 after two entries")
    void nextSequenceIsMonotonic(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceId -> pool.withTransaction(tx -> historyRepository
                                .append(entry(instanceId, 1L, WorkflowEntryType.START), tx)
                                .compose(v -> historyRepository.nextSequence(instanceId, tx)))
                        .compose(seq2 -> {
                            ctx.verify(() -> assertEquals(2L, seq2, "nextSequence after seq=1 must return 2"));
                            return pool.withTransaction(tx -> historyRepository
                                    .append(entry(instanceId, seq2, WorkflowEntryType.SIDE_EFFECT_RECORDED), tx)
                                    .compose(v -> historyRepository.nextSequence(instanceId, tx)));
                        })
                        .onSuccess(seq3 -> ctx.verify(() -> {
                            assertEquals(3L, seq3, "nextSequence after seq=2 must return 3");
                            ctx.completeNow();
                        }))
                        .onFailure(ctx::failNow))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("nextSequence is independent across instances: instance A and B have separate counters")
    void nextSequenceIsIndependentAcrossInstances(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceIdA ->
                        insertInstance().map(instanceIdB -> new WorkflowInstanceId[] {instanceIdA, instanceIdB}))
                .compose(ids -> {
                    WorkflowInstanceId idA = ids[0];
                    WorkflowInstanceId idB = ids[1];

                    // Append two entries to A, one entry to B, then check nextSequence for each
                    return pool.withTransaction(tx -> historyRepository
                                    .append(entry(idA, 1L, WorkflowEntryType.START), tx)
                                    .compose(v -> historyRepository.append(
                                            entry(idA, 2L, WorkflowEntryType.SIDE_EFFECT_RECORDED), tx))
                                    .compose(
                                            v -> historyRepository.append(entry(idB, 1L, WorkflowEntryType.START), tx)))
                            .compose(v -> Future.all(
                                    pool.withTransaction(tx -> historyRepository.nextSequence(idA, tx)),
                                    pool.withTransaction(tx -> historyRepository.nextSequence(idB, tx))))
                            .onSuccess(results -> ctx.verify(() -> {
                                Long seqA = results.resultAt(0);
                                Long seqB = results.resultAt(1);
                                assertEquals(3L, seqA, "instance A with 2 entries should have nextSequence=3");
                                assertEquals(2L, seqB, "instance B with 1 entry should have nextSequence=2");
                                ctx.completeNow();
                            }))
                            .onFailure(ctx::failNow);
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("listByInstance is scoped to the given instance (not affected by other instances)")
    void listByInstanceScopedToInstance(VertxTestContext ctx) {
        insertInstance()
                .compose(instanceIdA ->
                        insertInstance().map(instanceIdB -> new WorkflowInstanceId[] {instanceIdA, instanceIdB}))
                .compose(ids -> {
                    WorkflowInstanceId idA = ids[0];
                    WorkflowInstanceId idB = ids[1];

                    return pool.withTransaction(tx -> historyRepository
                                    .append(entry(idA, 1L, WorkflowEntryType.START), tx)
                                    .compose(v -> historyRepository.append(entry(idB, 1L, WorkflowEntryType.START), tx))
                                    .compose(v -> historyRepository.append(
                                            entry(idB, 2L, WorkflowEntryType.SIDE_EFFECT_RECORDED), tx)))
                            .compose(v -> historyRepository.listByInstance(idA))
                            .onSuccess(entriesA -> ctx.verify(() -> {
                                assertEquals(1, entriesA.size(), "listByInstance for A must only return A's entries");
                                assertEquals(idA, entriesA.get(0).instanceId());
                                ctx.completeNow();
                            }))
                            .onFailure(ctx::failNow);
                })
                .onFailure(ctx::failNow);
    }
}
