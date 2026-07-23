// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.List;
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
 * Integration tests for {@link PgWorkflowInstanceRepository} against a real PostgreSQL instance.
 *
 * <p>Verifies: insert + findById round-trip, findByIdForUpdate, findByBusinessKey (with and
 * without a business key), updateOptimistic (correct and stale version), and findFiltered keyset
 * pagination — filtering, ordering, forward/backward cursor navigation, boundary cases (empty
 * result, exact-pageful, page size larger than result set), and the null-cursor contract.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PgWorkflowInstanceRepositoryIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_instance")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgWorkflowInstanceRepository repository;

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
        repository = new PgWorkflowInstanceRepository(pool, new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query(
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                    } else {
                        ctx.completeNow();
                    }
                });
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Helpers ---

    /** Builds a minimal workflow instance with the given id and status, timestamped now. */
    private static WorkflowInstance instance(WorkflowInstanceId id, WorkflowStatus status) {
        Instant now = Instant.now();
        return instanceAt(id, status, now);
    }

    /**
     * Builds a minimal workflow instance with explicit {@code createdAt}/{@code updatedAt} so tests
     * that assert ordering by {@code updated_at} are deterministic and not subject to clock-tick
     * resolution.
     */
    private static WorkflowInstance instanceAt(WorkflowInstanceId id, WorkflowStatus status, Instant timestamp) {
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                status,
                null,
                null,
                "start",
                null,
                null,
                null,
                "{}",
                null,
                null,
                timestamp,
                timestamp,
                null);
    }

    /** Builds a minimal workflow instance carrying the given non-null durable metadata. */
    private static WorkflowInstance instanceWithMetadata(WorkflowInstanceId id, DurableMetadata metadata) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
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
                now,
                now,
                metadata);
    }

    /** Builds a workflow instance with a business key. */
    private static WorkflowInstance instanceWithBusinessKey(WorkflowInstanceId id, String businessKey) {
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                businessKey,
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
    }

    /** Builds a workflow instance with a subject ref. */
    private static WorkflowInstance instanceWithSubject(WorkflowInstanceId id, WorkflowSubjectRef subjectRef) {
        return new WorkflowInstance(
                id,
                "order-fulfillment",
                1L,
                "abc123hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                subjectRef,
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
    }

    // =========================================================================
    // insert + findById round-trip
    // =========================================================================

    @Test
    @DisplayName("insert + findById returns the same instance values")
    void insertAndFindByIdRoundTrip(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> repository.findById(id))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findById must return the inserted instance");
                    WorkflowInstance stored = found.get();
                    assertEquals(id, stored.id());
                    assertEquals("order-fulfillment", stored.definitionId());
                    assertEquals(1L, stored.definitionVersion());
                    assertEquals(WorkflowStatus.RUNNING, stored.status());
                    assertEquals("start", stored.currentStepId());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findById returns empty for an unknown id")
    void findByIdReturnsEmptyForUnknownId(VertxTestContext ctx) {
        WorkflowInstanceId unknownId = new WorkflowInstanceId(UUID.randomUUID());
        repository
                .findById(unknownId)
                .onSuccess(found -> ctx.verify(() -> {
                    assertFalse(found.isPresent(), "findById must return empty for an unknown id");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // Durable metadata persistence (PRD-WF-007 AC-1, AC-6)
    // =========================================================================

    @Test
    @DisplayName("insert + findById round-trips non-null metadata")
    void insertAndFindById_roundTripsNonNullMetadata(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        DurableMetadata metadata = DurableMetadata.of("tenant", new JsonObject().put("id", "T1"));
        WorkflowInstance inst = instanceWithMetadata(id, metadata);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> repository.findById(id))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findById must return the inserted instance");
                    assertEquals(
                            metadata,
                            found.get().metadata(),
                            "metadata must round-trip through JSONB byte-identical (namespace + body)");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("insert + findById round-trips null metadata with no empty-document artifact")
    void insertAndFindById_roundTripsNullMetadata(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> repository.findById(id))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findById must return the inserted instance");
                    assertNull(
                            found.get().metadata(),
                            "a null-metadata instance must round-trip as null, not an empty document");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("migratePinAndState leaves metadata untouched (AC-6)")
    void migratePinAndState_leavesMetadataUntouched(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        DurableMetadata metadata = DurableMetadata.of("tenant", new JsonObject().put("id", "T1"));
        WorkflowInstance inst = instanceWithMetadata(id, metadata);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> {
                    WorkflowInstance migrated = new WorkflowInstance(
                            inst.id(),
                            inst.definitionId(),
                            inst.definitionVersion() + 1,
                            "new-plan-hash",
                            1L,
                            inst.status(),
                            inst.businessKey(),
                            inst.subjectRef(),
                            "migrated-step",
                            inst.waitType(),
                            inst.waitKey(),
                            inst.waitAuxId(),
                            inst.stateJson(),
                            inst.errorType(),
                            inst.errorMessage(),
                            inst.createdAt(),
                            Instant.now(),
                            inst.metadata());
                    return pool.withTransaction(tx -> repository.migratePinAndState(migrated, 0L, tx));
                })
                .compose(v -> repository.findById(id))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertEquals(
                            metadata,
                            found.get().metadata(),
                            "migratePinAndState must leave metadata byte-identical (AC-6 immutability)");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateOptimistic leaves metadata untouched (AC-6)")
    void updateOptimistic_leavesMetadataUntouched(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        DurableMetadata metadata = DurableMetadata.of("tenant", new JsonObject().put("id", "T1"));
        WorkflowInstance inst = instanceWithMetadata(id, metadata);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> {
                    WorkflowInstance updated = new WorkflowInstance(
                            inst.id(),
                            inst.definitionId(),
                            inst.definitionVersion(),
                            inst.planHash(),
                            1L,
                            WorkflowStatus.WAITING,
                            inst.businessKey(),
                            inst.subjectRef(),
                            "wait-step",
                            WaitType.SIGNAL,
                            "payment-confirmed",
                            null,
                            inst.stateJson(),
                            inst.errorType(),
                            inst.errorMessage(),
                            inst.createdAt(),
                            Instant.now(),
                            inst.metadata());
                    return pool.withTransaction(tx -> repository.updateOptimistic(updated, 0L, tx));
                })
                .compose(v -> repository.findById(id))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertEquals(
                            metadata,
                            found.get().metadata(),
                            "updateOptimistic must leave metadata byte-identical (AC-6 immutability)");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // RowMappers blank-tolerance for subject columns
    //
    // The cycle-5 WorkflowSubjectRef compact constructor rejects blank values, so
    // RowMappers.workflowInstance() normalizes blank columns to null on the read path.
    // These tests inject rows directly via SQL to exercise the tolerance branch (the
    // write path through repository.insert can no longer produce blank values).
    // =========================================================================

    /** Inserts a minimal {@code workflow_instances} row directly via SQL with raw subject column values. */
    private io.vertx.core.Future<Void> insertRawSubjectRow(
            UUID id, String subjectType, String subjectId, String subjectVersion) {
        String sql = "INSERT INTO workflow_instances"
                + " (id, definition_id, definition_version, plan_hash, version, status,"
                + "  business_key, subject_type, subject_id, subject_version, current_step_id, state_json)"
                + " VALUES ($1, 'order-fulfillment', 1, 'hash', 0, 'RUNNING',"
                + "         NULL, $2, $3, $4, 'start', '{}')";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(id, subjectType, subjectId, subjectVersion))
                .<Void>mapEmpty();
    }

    @Test
    @DisplayName("RowMappers normalizes blank subject_type to subjectRef=null")
    void rowMapperBlankSubjectTypeNormalizesToNull(VertxTestContext ctx) {
        UUID id = UUID.randomUUID();
        insertRawSubjectRow(id, "", "art-1", "v3")
                .compose(v -> repository.findById(new WorkflowInstanceId(id)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertNull(found.get().subjectRef(), "blank subject_type must map to subjectRef=null");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("RowMappers normalizes blank subject_id to subjectRef=null")
    void rowMapperBlankSubjectIdNormalizesToNull(VertxTestContext ctx) {
        UUID id = UUID.randomUUID();
        insertRawSubjectRow(id, "Article", "", "v3")
                .compose(v -> repository.findById(new WorkflowInstanceId(id)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertNull(found.get().subjectRef(), "blank subject_id must map to subjectRef=null");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("RowMappers maps NULL subject columns to subjectRef=null")
    void rowMapperNullSubjectColumnsMapToNullRef(VertxTestContext ctx) {
        UUID id = UUID.randomUUID();
        insertRawSubjectRow(id, null, null, null)
                .compose(v -> repository.findById(new WorkflowInstanceId(id)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    assertNull(found.get().subjectRef(), "NULL subject columns must map to subjectRef=null");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("RowMappers normalizes blank subject_version to a versionless WorkflowSubjectRef")
    void rowMapperBlankSubjectVersionNormalizesToNullVersion(VertxTestContext ctx) {
        UUID id = UUID.randomUUID();
        insertRawSubjectRow(id, "Article", "art-1", "")
                .compose(v -> repository.findById(new WorkflowInstanceId(id)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    WorkflowSubjectRef ref = found.get().subjectRef();
                    assertNotNull(ref, "non-blank subject_type+subject_id must produce a WorkflowSubjectRef");
                    assertEquals("Article", ref.type());
                    assertEquals("art-1", ref.id());
                    assertNull(ref.version(), "blank subject_version must normalize to null");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("RowMappers builds a full WorkflowSubjectRef for non-blank columns")
    void rowMapperFullSubjectRefForNonBlankColumns(VertxTestContext ctx) {
        UUID id = UUID.randomUUID();
        insertRawSubjectRow(id, "Article", "art-1", "v3")
                .compose(v -> repository.findById(new WorkflowInstanceId(id)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    WorkflowSubjectRef ref = found.get().subjectRef();
                    assertNotNull(ref);
                    assertEquals("Article", ref.type());
                    assertEquals("art-1", ref.id());
                    assertEquals("v3", ref.version());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // findByIdForUpdate
    // =========================================================================

    @Test
    @DisplayName("findByIdForUpdate returns the row within a transaction")
    void findByIdForUpdateReturnsRow(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> pool.withTransaction(tx -> repository.findByIdForUpdate(id, tx)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findByIdForUpdate must return the row");
                    assertEquals(id, found.get().id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findByIdForUpdate returns empty for an unknown id")
    void findByIdForUpdateReturnsEmptyForUnknownId(VertxTestContext ctx) {
        WorkflowInstanceId unknownId = new WorkflowInstanceId(UUID.randomUUID());
        pool.withTransaction(tx -> repository.findByIdForUpdate(unknownId, tx))
                .onSuccess(found -> ctx.verify(() -> {
                    assertFalse(found.isPresent(), "findByIdForUpdate must return empty for an unknown id");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // findByBusinessKey
    // =========================================================================

    @Test
    @DisplayName("findByBusinessKey returns the row when business key is set")
    void findByBusinessKeyReturnsRow(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        String businessKey = "order-" + UUID.randomUUID();
        WorkflowInstance inst = instanceWithBusinessKey(id, businessKey);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v ->
                        pool.withTransaction(tx -> repository.findByBusinessKey("order-fulfillment", businessKey, tx)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent(), "findByBusinessKey must return the row");
                    assertEquals(id, found.get().id());
                    assertEquals(businessKey, found.get().businessKey());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findByBusinessKey returns empty when business key is null")
    void findByBusinessKeyReturnsEmptyWhenNull(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> pool.withTransaction(tx -> repository.findByBusinessKey("order-fulfillment", null, tx)))
                .onSuccess(found -> ctx.verify(() -> {
                    assertFalse(found.isPresent(), "findByBusinessKey with null key must return empty");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findByBusinessKey returns empty when the key does not match")
    void findByBusinessKeyReturnsEmptyForNonExistentKey(VertxTestContext ctx) {
        pool.withTransaction(tx -> repository.findByBusinessKey("order-fulfillment", "no-such-key", tx))
                .onSuccess(found -> ctx.verify(() -> {
                    assertFalse(found.isPresent(), "findByBusinessKey must return empty for a non-existent key");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // updateOptimistic
    // =========================================================================

    @Test
    @DisplayName("updateOptimistic returns 1 on correct version and increments the row version")
    void updateOptimisticCorrectVersionReturnsOne(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> {
                    WorkflowInstance updated = new WorkflowInstance(
                            inst.id(),
                            inst.definitionId(),
                            inst.definitionVersion(),
                            inst.planHash(),
                            1L,
                            WorkflowStatus.WAITING,
                            inst.businessKey(),
                            inst.subjectRef(),
                            "wait-step",
                            WaitType.SIGNAL,
                            "payment-confirmed",
                            null,
                            inst.stateJson(),
                            inst.errorType(),
                            inst.errorMessage(),
                            inst.createdAt(),
                            Instant.now(),
                            inst.metadata());
                    return pool.withTransaction(tx -> repository.updateOptimistic(updated, 0L, tx));
                })
                .compose(updatedCount -> repository.findById(id).map(found -> {
                    assertEquals(1, updatedCount, "updateOptimistic must return 1 on correct version");
                    return found;
                }))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isPresent());
                    WorkflowInstance stored = found.get();
                    assertEquals(1L, stored.version(), "version must be incremented after update");
                    assertEquals(WorkflowStatus.WAITING, stored.status());
                    assertEquals("wait-step", stored.currentStepId());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateOptimistic clears completed_at when transitioning to a non-terminal status")
    void updateOptimisticClearsCompletedAtOnNonTerminal(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance failed = instance(id, WorkflowStatus.FAILED);

        pool.withTransaction(tx -> repository.insert(failed, tx))
                // Force completed_at to a real value (simulating prior terminal commit)
                .compose(v -> pool.preparedQuery("UPDATE workflow_instances SET completed_at = NOW() WHERE id = $1")
                        .execute(Tuple.of(id.value()))
                        .mapEmpty())
                // Sanity: completed_at is non-null before retry
                .compose(v -> pool.preparedQuery("SELECT completed_at FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value()))
                        .map(rs -> rs.iterator().next().getOffsetDateTime("completed_at")))
                .compose(beforeRetry -> {
                    assertNotNull(beforeRetry, "completed_at must be set after FAILED commit");
                    // Now simulate retry: same instance back to RUNNING with version+1
                    WorkflowInstance retried = new WorkflowInstance(
                            failed.id(),
                            failed.definitionId(),
                            failed.definitionVersion(),
                            failed.planHash(),
                            1L,
                            WorkflowStatus.RUNNING,
                            failed.businessKey(),
                            failed.subjectRef(),
                            failed.currentStepId(),
                            failed.waitType(),
                            failed.waitKey(),
                            null,
                            failed.stateJson(),
                            null,
                            null,
                            failed.createdAt(),
                            Instant.now(),
                            failed.metadata());
                    return pool.withTransaction(tx -> repository.updateOptimistic(retried, 0L, tx));
                })
                .compose(rows -> {
                    assertEquals(1, rows, "retry update must succeed");
                    return pool.preparedQuery("SELECT completed_at FROM workflow_instances WHERE id = $1")
                            .execute(Tuple.of(id.value()))
                            .map(rs -> rs.iterator().next().getOffsetDateTime("completed_at"));
                })
                .onSuccess(afterRetry -> ctx.verify(() -> {
                    assertTrue(
                            afterRetry == null,
                            "completed_at must be cleared on FAILED→RUNNING transition; got " + afterRetry);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateOptimistic returns 0 on stale version (concurrent update wins)")
    void updateOptimisticStaleVersionReturnsZero(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance inst = instance(id, WorkflowStatus.RUNNING);

        pool.withTransaction(tx -> repository.insert(inst, tx))
                .compose(v -> {
                    WorkflowInstance updated = new WorkflowInstance(
                            inst.id(),
                            inst.definitionId(),
                            inst.definitionVersion(),
                            inst.planHash(),
                            1L,
                            WorkflowStatus.COMPLETED,
                            inst.businessKey(),
                            inst.subjectRef(),
                            "complete",
                            null,
                            null,
                            null,
                            inst.stateJson(),
                            null,
                            null,
                            inst.createdAt(),
                            Instant.now(),
                            inst.metadata());
                    // Pass expectedVersion=5 when actual DB version is 0 → stale
                    return pool.withTransaction(tx -> repository.updateOptimistic(updated, 5L, tx));
                })
                .onSuccess(count -> ctx.verify(() -> {
                    assertEquals(0, count, "updateOptimistic must return 0 on stale version");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // findFiltered
    // =========================================================================

    @Test
    @DisplayName("findFiltered by status returns only matching instances in updated_at DESC order")
    void findFilteredByStatusReturnsMatchingInstances(VertxTestContext ctx) {
        WorkflowInstanceId runningId = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId waitingId = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId completedId = new WorkflowInstanceId(UUID.randomUUID());

        pool.withTransaction(tx -> repository
                        .insert(instance(runningId, WorkflowStatus.RUNNING), tx)
                        .compose(v -> repository.insert(instance(waitingId, WorkflowStatus.WAITING), tx))
                        .compose(v -> repository.insert(instance(completedId, WorkflowStatus.COMPLETED), tx)))
                .compose(v -> repository.findFiltered(
                        WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertNotNull(result);
                    assertEquals(1, result.items().size(), "findFiltered by RUNNING must return exactly 1 instance");
                    assertEquals(runningId, result.items().get(0).id());
                    assertFalse(result.hasMore(), "single-result page must not advertise a next page");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered with no criteria returns all instances and a single-page result")
    void findFilteredNoCriteriaReturnsAll(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());

        pool.withTransaction(tx -> repository
                        .insert(instance(id1, WorkflowStatus.RUNNING), tx)
                        .compose(v -> repository.insert(instance(id2, WorkflowStatus.COMPLETED), tx)))
                .compose(v -> repository.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertNotNull(result);
                    assertEquals(2, result.items().size(), "findFiltered with no criteria must return all instances");
                    assertFalse(result.hasMore(), "page size larger than result set must not advertise a next page");
                    assertNull(
                            result.nextCursorToken(),
                            "page size larger than result set must have null nextCursorToken");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered with no matching rows returns an empty result and no cursor tokens")
    void findFilteredEmptyResult(VertxTestContext ctx) {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());

        pool.withTransaction(tx -> repository.insert(instance(id, WorkflowStatus.RUNNING), tx))
                .compose(v -> repository.findFiltered(
                        WorkflowInstanceQuery.byStatus(WorkflowStatus.COMPLETED), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertNotNull(result);
                    assertTrue(result.items().isEmpty(), "no-match filter must return zero items");
                    assertFalse(result.hasMore(), "empty result must not advertise a next page");
                    assertFalse(result.hasPrevious(), "empty first-page result must not advertise a previous page");
                    assertNull(result.nextCursorToken(), "empty result must have null nextCursorToken");
                    assertNull(result.previousCursorToken(), "empty result must have null previousCursorToken");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered with rows == pageSize returns one full page and no continuation")
    void findFilteredExactlyOnePageful(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());

        pool.withTransaction(tx -> repository.insert(instance(id1, WorkflowStatus.RUNNING), tx))
                .compose(v -> pool.withTransaction(tx -> repository.insert(instance(id2, WorkflowStatus.RUNNING), tx)))
                .compose(v -> repository.findFiltered(
                        WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING), PageCursor.first(2)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(2, result.items().size(), "exact-pageful must return all rows");
                    assertFalse(result.hasMore(), "exact-pageful must not advertise a next page");
                    assertNull(result.nextCursorToken(), "exact-pageful must have null nextCursorToken");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered with a null cursor throws NullPointerException synchronously")
    void findFilteredNullCursorThrowsNpe() {
        // Documented contract: PgPagedQuery.page(PageCursor) calls Objects.requireNonNull(cursor)
        // before any async boundary, so the NPE surfaces synchronously on the caller's thread.
        assertThrows(NullPointerException.class, () -> repository.findFiltered(WorkflowInstanceQuery.none(), null));
    }

    @Test
    @DisplayName("findFiltered cursor navigates forward and backward across pages")
    void findFilteredCursorNavigatesPages(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id3 = new WorkflowInstanceId(UUID.randomUUID());

        // Explicit, well-separated timestamps so updated_at DESC ordering is deterministic
        // independent of clock resolution. id3 (newest) → id2 → id1 (oldest).
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = base;
        Instant t2 = base.plusSeconds(60);
        Instant t3 = base.plusSeconds(120);

        pool.withTransaction(tx -> repository.insert(instanceAt(id1, WorkflowStatus.RUNNING, t1), tx))
                .compose(v ->
                        pool.withTransaction(tx -> repository.insert(instanceAt(id2, WorkflowStatus.RUNNING, t2), tx)))
                .compose(v ->
                        pool.withTransaction(tx -> repository.insert(instanceAt(id3, WorkflowStatus.RUNNING, t3), tx)))
                .compose(v -> repository.findFiltered(
                        WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING), PageCursor.first(2)))
                .compose(page1 -> {
                    assertEquals(2, page1.items().size(), "first page must have 2 results");
                    assertTrue(page1.hasMore(), "first page must advertise a next page");
                    assertNotNull(page1.nextCursorToken(), "first page must expose nextCursorToken");
                    assertFalse(page1.hasPrevious(), "first page must not advertise a previous page");
                    return repository
                            .findFiltered(
                                    WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING),
                                    PageCursor.fromToken(page1.nextCursorToken()))
                            .map(page2 -> List.of(page1, page2));
                })
                .compose(pages -> {
                    PagedResult<WorkflowInstance> page1 = pages.get(0);
                    PagedResult<WorkflowInstance> page2 = pages.get(1);
                    assertEquals(1, page2.items().size(), "second page must have 1 result");
                    assertFalse(page2.hasMore(), "second page must not advertise a next page");
                    assertNull(page2.nextCursorToken(), "last page must have null nextCursorToken");
                    assertTrue(page2.hasPrevious(), "non-first page must advertise a previous page");
                    assertNotNull(page2.previousCursorToken(), "non-first page must expose previousCursorToken");
                    return repository
                            .findFiltered(
                                    WorkflowInstanceQuery.byStatus(WorkflowStatus.RUNNING),
                                    PageCursor.fromToken(page2.previousCursorToken()))
                            .map(backPage -> List.of(page1, backPage));
                })
                .onSuccess(pages -> ctx.verify(() -> {
                    PagedResult<WorkflowInstance> page1 = pages.get(0);
                    PagedResult<WorkflowInstance> backPage = pages.get(1);
                    assertEquals(
                            page1.items().size(),
                            backPage.items().size(),
                            "backward page must return the same number of rows as the original first page");
                    assertEquals(
                            page1.items().get(0).id(),
                            backPage.items().get(0).id(),
                            "backward page leading row must match original first page leading row");
                    assertEquals(
                            page1.items().get(1).id(),
                            backPage.items().get(1).id(),
                            "backward page trailing row must match original first page trailing row");
                    assertTrue(backPage.hasMore(), "backward page must advertise a next page (page 2 still exists)");
                    assertNotNull(backPage.nextCursorToken(), "backward page must expose nextCursorToken");
                    assertFalse(
                            backPage.hasPrevious(),
                            "backward page is the first page; it must not advertise a previous page");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered by definitionId returns only matching instances")
    void findFilteredByDefinitionIdReturnsMatchingInstances(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());

        WorkflowInstance otherDefInst = new WorkflowInstance(
                id2,
                "payment-processing",
                1L,
                "xyz",
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

        pool.withTransaction(tx -> repository
                        .insert(instance(id1, WorkflowStatus.RUNNING), tx)
                        .compose(v -> repository.insert(otherDefInst, tx)))
                .compose(v -> repository.findFiltered(
                        WorkflowInstanceQuery.byDefinitionId("order-fulfillment"), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(
                            1,
                            result.items().size(),
                            "findFiltered by definitionId must return only matching instances");
                    assertEquals(id1, result.items().get(0).id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered by subjectRef returns only matching instances")
    void findFilteredBySubjectRefReturnsMatchingInstances(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowSubjectRef subject = new WorkflowSubjectRef("Order", "order-99", null);

        pool.withTransaction(tx -> repository
                        .insert(instanceWithSubject(id1, subject), tx)
                        .compose(v -> repository.insert(instance(id2, WorkflowStatus.RUNNING), tx)))
                .compose(v -> {
                    WorkflowInstanceQuery filter =
                            new WorkflowInstanceQuery(null, null, "Order", "order-99", null, null, false);
                    return repository.findFiltered(filter, PageCursor.first(10));
                })
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(
                            1, result.items().size(), "findFiltered by subjectRef must return only matching instances");
                    assertEquals(id1, result.items().get(0).id());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findFiltered result order is stable (updated_at DESC)")
    void findFilteredOrderIsStableByUpdatedAtDesc(VertxTestContext ctx) {
        WorkflowInstanceId id1 = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId id2 = new WorkflowInstanceId(UUID.randomUUID());

        // Explicit timestamps: id2 is newer than id1 by a full minute, so updated_at DESC must
        // place id2 first regardless of insertion order or clock-tick resolution.
        Instant tOlder = Instant.parse("2026-01-01T00:00:00Z");
        Instant tNewer = tOlder.plusSeconds(60);

        pool.withTransaction(tx -> repository.insert(instanceAt(id1, WorkflowStatus.RUNNING, tOlder), tx))
                .compose(v -> pool.withTransaction(
                        tx -> repository.insert(instanceAt(id2, WorkflowStatus.RUNNING, tNewer), tx)))
                .compose(v -> repository.findFiltered(WorkflowInstanceQuery.none(), PageCursor.first(10)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(2, result.items().size());
                    List<WorkflowInstanceId> ids =
                            result.items().stream().map(WorkflowInstance::id).toList();
                    assertEquals(id2, ids.get(0), "most recently updated instance must appear first");
                    assertEquals(id1, ids.get(1));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
