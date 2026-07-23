// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
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
 * Integration tests for the cycle-3 dedup methods on {@link PgWorkflowDedupRepository}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@link PgWorkflowDedupRepository#claimOrResolveTaskCompletion} — first-call insert,
 *       idempotent retry, fingerprint conflict, kind discrimination, scope isolation.</li>
 *   <li>{@link PgWorkflowDedupRepository#claimOrResolveTaskReassignment} — same semantics under
 *       {@code kind='task-reassign'}.</li>
 *   <li>Cross-kind non-collision: same {@code (taskId, idempotencyKey)} succeeds for both
 *       {@code 'task-complete'} and {@code 'task-reassign'}.</li>
 *   <li>Cycle-1 {@code claimOrResolveStart} writes a row with {@code fingerprint IS NULL}.</li>
 *   <li>Cycle-2 {@code claimOrResolveSignal} writes a row with {@code fingerprint IS NULL}.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowDedupRepositoryTaskIT {

    // --- Testcontainers setup ---

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_dedup_task")
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
        pool.query("TRUNCATE TABLE workflow_timers, workflow_tasks, workflow_history, workflow_dedup,"
                        + " workflow_instances RESTART IDENTITY CASCADE")
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
     * Inserts a minimal {@code workflow_instances} row to satisfy the FK on
     * {@code workflow_dedup.workflow_id}.
     *
     * @param id the workflow instance id to insert
     * @return a {@link Future} that completes when the row is inserted
     */
    private Future<Void> insertInstance(WorkflowInstanceId id) {
        WorkflowInstance inst = new WorkflowInstance(
                id,
                "task-dedup-def",
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

    /**
     * Reads the {@code fingerprint} column from {@code workflow_dedup} for the given row.
     *
     * @param kind  the dedup kind (e.g., {@code "start"}, {@code "signal"})
     * @param scope the dedup scope
     * @param key   the dedup key
     * @return a {@link Future} containing the fingerprint value (may be {@code null})
     */
    private Future<String> readFingerprint(String kind, String scope, String key) {
        return pool.preparedQuery("SELECT fingerprint FROM workflow_dedup WHERE kind = $1 AND scope = $2 AND key = $3")
                .execute(Tuple.of(kind, scope, key))
                .map(rs -> {
                    var it = rs.iterator();
                    assertTrue(it.hasNext(), "dedup row must exist for kind=" + kind + " scope=" + scope);
                    Row row = it.next();
                    return row.getString("fingerprint");
                });
    }

    // =========================================================================
    // claimOrResolveTaskCompletion
    // =========================================================================

    /**
     * First call inserts the row and returns {@code DedupClaim(inserted=true, fp=fingerprint)}.
     */
    @Test
    @DisplayName("claimOrResolveTaskCompletion first call returns inserted=true with the fingerprint")
    void taskCompletionFirstCallReturnsInsertedTrue(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "complete-" + UUID.randomUUID();
        String fingerprint = "fp-abc123";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskCompletion(taskId, idemKey, workflowId, fingerprint, tx)))
                .onSuccess(claim -> ctx.verify(() -> {
                    assertNotNull(claim);
                    assertTrue(claim.inserted(), "first call must return inserted=true");
                    assertEquals(
                            fingerprint, claim.existingFingerprint(), "existingFingerprint must equal the written fp");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Second call with same {@code (taskId, idempotencyKey, fingerprint)} returns
     * {@code DedupClaim(inserted=false, existingFingerprint=ORIGINAL_fp)}.
     */
    @Test
    @DisplayName("claimOrResolveTaskCompletion second call with same key+fingerprint returns inserted=false")
    void taskCompletionSecondCallSameFingerprintReturnsInsertedFalse(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "complete-idem-" + UUID.randomUUID();
        String fingerprint = "fp-same-123";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskCompletion(taskId, idemKey, workflowId, fingerprint, tx)))
                .compose(firstClaim -> {
                    assertTrue(firstClaim.inserted(), "first claim must succeed");
                    return pool.withTransaction(tx ->
                            dedupRepository.claimOrResolveTaskCompletion(taskId, idemKey, workflowId, fingerprint, tx));
                })
                .onSuccess(secondClaim -> ctx.verify(() -> {
                    assertFalse(secondClaim.inserted(), "second call must return inserted=false");
                    assertEquals(
                            fingerprint, secondClaim.existingFingerprint(), "must return the ORIGINAL fingerprint");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Second call with same {@code (taskId, idempotencyKey)} but DIFFERENT fingerprint returns
     * {@code DedupClaim(inserted=false, existingFingerprint=ORIGINAL_fp)} — NOT overwritten.
     */
    @Test
    @DisplayName("claimOrResolveTaskCompletion second call with different fingerprint returns original fingerprint")
    void taskCompletionSecondCallDifferentFingerprintReturnsOriginal(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "complete-conflict-" + UUID.randomUUID();
        String originalFp = "fp-original";
        String conflictFp = "fp-different";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskCompletion(taskId, idemKey, workflowId, originalFp, tx)))
                .compose(firstClaim -> {
                    assertTrue(firstClaim.inserted());
                    return pool.withTransaction(tx ->
                            dedupRepository.claimOrResolveTaskCompletion(taskId, idemKey, workflowId, conflictFp, tx));
                })
                .onSuccess(secondClaim -> ctx.verify(() -> {
                    assertFalse(secondClaim.inserted(), "second call must return inserted=false");
                    assertEquals(
                            originalFp,
                            secondClaim.existingFingerprint(),
                            "existingFingerprint must be the ORIGINAL fp, not overwritten by the conflict fp");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // claimOrResolveTaskReassignment
    // =========================================================================

    /**
     * {@link PgWorkflowDedupRepository#claimOrResolveTaskReassignment} first call returns
     * {@code DedupClaim(inserted=true, existingFingerprint=fp)}.
     */
    @Test
    @DisplayName("claimOrResolveTaskReassignment first call returns inserted=true with the fingerprint")
    void taskReassignmentFirstCallReturnsInsertedTrue(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "reassign-" + UUID.randomUUID();
        String fingerprint = "fp-reassign-abc";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskReassignment(taskId, idemKey, workflowId, fingerprint, tx)))
                .onSuccess(claim -> ctx.verify(() -> {
                    assertTrue(claim.inserted(), "first reassign claim must return inserted=true");
                    assertEquals(fingerprint, claim.existingFingerprint());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Second call with same {@code (taskId, idempotencyKey, fingerprint)} for reassignment returns
     * {@code DedupClaim(inserted=false, existingFingerprint=ORIGINAL_fp)}.
     */
    @Test
    @DisplayName("claimOrResolveTaskReassignment second call with same key+fingerprint returns inserted=false")
    void taskReassignmentSecondCallSameFingerprintReturnsInsertedFalse(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "reassign-idem-" + UUID.randomUUID();
        String fingerprint = "fp-reassign-same";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskReassignment(taskId, idemKey, workflowId, fingerprint, tx)))
                .compose(firstClaim -> {
                    assertTrue(firstClaim.inserted());
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveTaskReassignment(
                            taskId, idemKey, workflowId, fingerprint, tx));
                })
                .onSuccess(secondClaim -> ctx.verify(() -> {
                    assertFalse(secondClaim.inserted());
                    assertEquals(fingerprint, secondClaim.existingFingerprint());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Second call with same {@code (taskId, idempotencyKey)} but DIFFERENT fingerprint for
     * reassignment returns the original fingerprint — not overwritten.
     */
    @Test
    @DisplayName("claimOrResolveTaskReassignment second call with different fingerprint returns original fingerprint")
    void taskReassignmentSecondCallDifferentFingerprintReturnsOriginal(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String idemKey = "reassign-conflict-" + UUID.randomUUID();
        String originalFp = "fp-reassign-orig";
        String conflictFp = "fp-reassign-diff";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskReassignment(taskId, idemKey, workflowId, originalFp, tx)))
                .compose(firstClaim -> {
                    assertTrue(firstClaim.inserted());
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveTaskReassignment(
                            taskId, idemKey, workflowId, conflictFp, tx));
                })
                .onSuccess(secondClaim -> ctx.verify(() -> {
                    assertFalse(secondClaim.inserted());
                    assertEquals(
                            originalFp,
                            secondClaim.existingFingerprint(),
                            "existingFingerprint must be the ORIGINAL fp");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Kind discrimination
    // =========================================================================

    /**
     * Same {@code (taskId, idempotencyKey)} used for BOTH {@code 'task-complete'} AND
     * {@code 'task-reassign'} does NOT collide — both first-call inserts succeed because the kind
     * discriminator partitions the namespace.
     */
    @Test
    @DisplayName("same (taskId, idempotencyKey) for task-complete and task-reassign does not collide")
    void sameKeyForCompleteAndReassignDoesNotCollide(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        String sharedKey = "shared-idem-" + UUID.randomUUID();
        String completeFp = "fp-complete";
        String reassignFp = "fp-reassign";

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(tx ->
                        dedupRepository.claimOrResolveTaskCompletion(taskId, sharedKey, workflowId, completeFp, tx)))
                .compose(completeClaim -> {
                    assertTrue(completeClaim.inserted(), "task-complete first call must succeed");
                    return pool.withTransaction(tx -> dedupRepository.claimOrResolveTaskReassignment(
                            taskId, sharedKey, workflowId, reassignFp, tx));
                })
                .onSuccess(reassignClaim -> ctx.verify(() -> {
                    assertTrue(
                            reassignClaim.inserted(), "task-reassign with same key must also succeed (different kind)");
                    assertEquals(reassignFp, reassignClaim.existingFingerprint());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Scope isolation
    // =========================================================================

    /**
     * Different {@code taskId} with the same {@code idempotencyKey} does NOT collide — each taskId
     * forms its own dedup scope.
     */
    @Test
    @DisplayName("different taskId with same idempotencyKey does not collide")
    void differentTaskIdWithSameKeyDoesNotCollide(VertxTestContext ctx) {
        WorkflowInstanceId workflowIdA = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId workflowIdB = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskIdA = UUID.randomUUID();
        UUID taskIdB = UUID.randomUUID();
        String sharedKey = "scope-idem-" + UUID.randomUUID();
        String fpA = "fp-scope-a";
        String fpB = "fp-scope-b";

        insertInstance(workflowIdA)
                .compose(v -> insertInstance(workflowIdB))
                .compose(v -> pool.withTransaction(
                        tx -> dedupRepository.claimOrResolveTaskCompletion(taskIdA, sharedKey, workflowIdA, fpA, tx)))
                .compose(claimA -> {
                    assertTrue(claimA.inserted(), "taskIdA claim must succeed");
                    return pool.withTransaction(tx ->
                            dedupRepository.claimOrResolveTaskCompletion(taskIdB, sharedKey, workflowIdB, fpB, tx));
                })
                .onSuccess(claimB -> ctx.verify(() -> {
                    assertTrue(
                            claimB.inserted(), "taskIdB claim with same idemKey must also succeed (different scope)");
                    assertEquals(fpB, claimB.existingFingerprint());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Null-fingerprint assertions for cycle-1 (start) and cycle-2 (signal)
    // =========================================================================

    /**
     * {@link PgWorkflowDedupRepository#claimOrResolveStart} writes the supplied fingerprint into
     * the {@code fingerprint} column and returns it in {@link
     * dev.vertique.workflow.engine.spi.StartDedupResult#existingFingerprint()}.
     */
    @Test
    @DisplayName("claimOrResolveStart writes the supplied fingerprint and returns it")
    void startDedupRowHasFingerprint(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        String definitionId = "task-dedup-def";
        String idemKey = "start-fp-" + UUID.randomUUID();
        String expectedFingerprint = "definitionVersion=1";

        insertInstance(id)
                .compose(v -> pool.withTransaction(
                        tx -> dedupRepository.claimOrResolveStart(definitionId, idemKey, id, expectedFingerprint, tx)))
                .compose(result -> {
                    assertTrue(result.didInsert(), "start claim must succeed");
                    assertEquals(
                            expectedFingerprint,
                            result.existingFingerprint(),
                            "existingFingerprint must equal the fingerprint written on insert");
                    return readFingerprint("start", definitionId, idemKey);
                })
                .onSuccess(fingerprint -> ctx.verify(() -> {
                    assertEquals(expectedFingerprint, fingerprint, "persisted fingerprint must match");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    /**
     * Cycle-2 {@link PgWorkflowDedupRepository#claimOrResolveSignal} writes a row with
     * {@code fingerprint IS NULL}.
     */
    @Test
    @DisplayName("claimOrResolveSignal writes a row with fingerprint IS NULL")
    void signalDedupRowHasNullFingerprint(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        String signalDedupKey = "sig-fp-null-" + UUID.randomUUID();
        String scope = workflowId.value().toString();

        insertInstance(workflowId)
                .compose(v -> pool.withTransaction(
                        tx -> dedupRepository.claimOrResolveSignal(workflowId, signalDedupKey, tx)))
                .compose(inserted -> {
                    assertTrue(inserted, "signal claim must succeed");
                    return readFingerprint("signal", scope, signalDedupKey);
                })
                .onSuccess(fingerprint -> ctx.verify(() -> {
                    assertNull(fingerprint, "claimOrResolveSignal must write fingerprint IS NULL");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
