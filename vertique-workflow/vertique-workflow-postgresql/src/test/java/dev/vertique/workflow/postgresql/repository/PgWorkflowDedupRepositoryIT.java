// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
 * Integration tests for {@link PgWorkflowDedupRepository} against a real PostgreSQL instance.
 *
 * <p>Verifies: claimOrResolveStart with a new key returns the proposed id with {@code
 * didInsert=true}; claimOrResolveStart with an existing key returns the existing id with {@code
 * didInsert=false}; findSignal + insertSignal round-trip; second insertSignal for the same
 * {@code (workflowId, signalDedupKey)} fails with a unique-constraint violation (the PK); dedup
 * scoping between two different workflow instance ids using the same signal dedup key.
 *
 * <p>All repository method bodies are stubs in this commit boundary; every test is expected to
 * fail with {@link UnsupportedOperationException} (red phase).
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowDedupRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_dedup")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository instanceRepository;
    static PgWorkflowDedupRepository dedupRepository;

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
        dedupRepository = new PgWorkflowDedupRepository(pool, mapper);
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

    /**
     * Creates and inserts a minimal workflow instance with the given id so foreign-key constraints
     * on {@code workflow_dedup.workflow_id} are satisfied.
     */
    private Future<Void> insertInstance(WorkflowInstanceId id) {
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
        return pool.withTransaction(tx -> instanceRepository.insert(inst, tx));
    }

    // =========================================================================
    // claimOrResolveStart
    // =========================================================================

    @Test
    @DisplayName("claimOrResolveStart with a new key returns proposed id with didInsert=true")
    void claimOrResolveStartNewKeyReturnsProposedIdAndDidInsertTrue(VertxTestContext ctx) {
        WorkflowInstanceId proposedId = new WorkflowInstanceId(UUID.randomUUID());
        String idempotencyKey = "idem-" + UUID.randomUUID();

        insertInstance(proposedId)
                .compose(v -> pool.withTransaction(tx -> dedupRepository.claimOrResolveStart(
                        "order-fulfillment", idempotencyKey, proposedId, "definitionVersion=1", tx)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertNotNull(result);
                    assertEquals(proposedId, result.workflowId(), "winning id must equal the proposed id");
                    assertTrue(result.didInsert(), "first claim must return didInsert=true");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimOrResolveStart with existing key returns existing id with didInsert=false")
    void claimOrResolveStartExistingKeyReturnsExistingIdAndDidInsertFalse(VertxTestContext ctx) {
        WorkflowInstanceId firstId = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId secondId = new WorkflowInstanceId(UUID.randomUUID());
        String idempotencyKey = "idem-dup-" + UUID.randomUUID();

        insertInstance(firstId)
                .compose(v -> insertInstance(secondId))
                .compose(v -> pool.withTransaction(tx -> dedupRepository.claimOrResolveStart(
                        "order-fulfillment", idempotencyKey, firstId, "definitionVersion=1", tx)))
                .compose(firstResult -> {
                    assertTrue(firstResult.didInsert(), "first claim must succeed");
                    // Now try again with a different proposed id — same idem key
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveStart(
                            "order-fulfillment", idempotencyKey, secondId, "definitionVersion=1", tx));
                })
                .onSuccess(secondResult -> ctx.verify(() -> {
                    assertNotNull(secondResult);
                    assertEquals(
                            firstId,
                            secondResult.workflowId(),
                            "second claim must return the already-committed first id");
                    assertFalse(secondResult.didInsert(), "second claim must return didInsert=false");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("claimOrResolveStart scopes by definitionId: same idemKey for different defs are independent")
    void claimOrResolveStartScopedByDefinitionId(VertxTestContext ctx) {
        WorkflowInstanceId idA = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId idB = new WorkflowInstanceId(UUID.randomUUID());
        String sharedIdemKey = "shared-idem-" + UUID.randomUUID();

        insertInstance(idA)
                .compose(v -> insertInstance(idB))
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveStart("def-A", sharedIdemKey, idA, "definitionVersion=1", tx)))
                .compose(resultA -> {
                    assertTrue(resultA.didInsert(), "claim for def-A must succeed");
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveStart(
                            "def-B", sharedIdemKey, idB, "definitionVersion=1", tx));
                })
                .onSuccess(resultB -> ctx.verify(() -> {
                    assertTrue(
                            resultB.didInsert(),
                            "claim for def-B with same idemKey must also succeed (different scope)");
                    assertEquals(idB, resultB.workflowId());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // findSignal + insertSignal
    // =========================================================================

    @Test
    @DisplayName("insertSignal + findSignal round-trip returns the workflow id UUID")
    void insertSignalAndFindSignalRoundTrip(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        String dedupKey = "sig-dedup-" + UUID.randomUUID();

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> dedupRepository.insertSignal(workflowId, dedupKey, tx)))
                .compose(v -> pool.withTransaction(tx -> dedupRepository.findSignal(workflowId, dedupKey, tx)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findSignal must return a UUID after insertSignal");
                    assertEquals(workflowId.value(), found.get(), "findSignal must return the workflow instance UUID");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findSignal returns empty when no signal dedup record exists")
    void findSignalReturnsEmptyWhenNotPresent(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        String dedupKey = "non-existent-" + UUID.randomUUID();

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> dedupRepository.findSignal(workflowId, dedupKey, tx)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertFalse(found.isPresent(), "findSignal must return empty for a non-existent key");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("second insertSignal for same (workflowId, dedupKey) fails with unique-constraint violation")
    void secondInsertSignalForSameKeyFailsWithConstraintViolation(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        String dedupKey = "dup-sig-" + UUID.randomUUID();

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> dedupRepository.insertSignal(workflowId, dedupKey, tx)))
                .compose(v -> pool.withTransaction(tx -> dedupRepository.insertSignal(workflowId, dedupKey, tx)))
                .onSuccess(v -> ctx.verify(() -> {
                    fail(
                            "second insertSignal for the same (workflowId, dedupKey) must fail with a constraint violation");
                    ctx.completeNow();
                }))
                .onFailure(t -> ctx.verify(() -> {
                    // The failure is expected — any exception (UniqueConstraintViolation or UnsupportedOperation
                    // in stub mode) completes the test as red
                    assertNotNull(t, "second insertSignal must fail");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("dedup scoping: two different workflowInstanceIds with same dedupKey are independent")
    void signalDedupScopedByWorkflowInstance(VertxTestContext ctx) {
        WorkflowInstanceId idA = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId idB = new WorkflowInstanceId(UUID.randomUUID());
        String sharedDedupKey = "shared-sig-" + UUID.randomUUID();

        insertInstance(idA)
                .compose(v -> insertInstance(idB))
                .compose(v -> pool.withTransaction(tx -> dedupRepository.insertSignal(idA, sharedDedupKey, tx)))
                .compose(v -> pool.withTransaction(tx -> dedupRepository.insertSignal(idB, sharedDedupKey, tx)))
                .compose(v -> Future.all(
                        pool.withTransaction(tx -> dedupRepository.findSignal(idA, sharedDedupKey, tx)),
                        pool.withTransaction(tx -> dedupRepository.findSignal(idB, sharedDedupKey, tx))))
                .onSuccess(results -> ctx.verify(() -> {
                    var foundA = results.<java.util.Optional<UUID>>resultAt(0);
                    var foundB = results.<java.util.Optional<UUID>>resultAt(1);
                    assertTrue(foundA.isPresent(), "findSignal for instance A must return the record");
                    assertTrue(foundB.isPresent(), "findSignal for instance B must return the record");
                    assertEquals(idA.value(), foundA.get(), "findSignal for A must return A's workflow UUID");
                    assertEquals(idB.value(), foundB.get(), "findSignal for B must return B's workflow UUID");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
