// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStatusTransition;
import io.vertx.core.Vertx;
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
 * Integration tests for {@link PgTimerStore} against a real PostgreSQL instance.
 *
 * <p>Verifies: insert + lockForFiring round-trip; {@code markFired}, {@code markCancelled}, and
 * {@code markFailed} return {@link TimerStatusTransition#APPLIED} on first call and the
 * appropriate {@code LOST_TO_*} constant on a second conflicting call; {@code
 * findRecoverableScheduled} returns only past-fire-at SCHEDULED rows ordered by {@code fire_at};
 * {@code updateExecutionId} updates without changing status; CASCADE delete on the parent
 * {@code workflow_instances} row removes the timer row.
 *
 * <p>Each test is self-contained and truncates all relevant tables in {@link #truncateTables}.
 * Operations run inside a {@code pool.withTransaction(...)} call to exercise the transactional path.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgTimerStoreIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_test_timer")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static PgTimerStore store;

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
        store = new PgTimerStore(new PgDbExceptionMapper());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup,"
                        + " workflow_instances RESTART IDENTITY CASCADE")
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

    /**
     * Inserts a minimal {@code workflow_instances} row so the FK on {@code workflow_timers.workflow_id}
     * is satisfied.
     *
     * @param workflowId the id of the parent instance to seed
     * @return a {@link io.vertx.core.Future} that completes when the row is inserted
     */
    private io.vertx.core.Future<Void> seedInstance(WorkflowInstanceId workflowId) {
        return pool.preparedQuery("INSERT INTO workflow_instances"
                        + " (id, definition_id, definition_version, plan_hash, version,"
                        + "  status, current_step_id, state_json, created_at, updated_at)"
                        + " VALUES ($1, 'test-def', 1, 'hash1', 0,"
                        + "  $2, 'start', '{}', NOW(), NOW())")
                .execute(Tuple.of(workflowId.value(), WorkflowStatus.RUNNING.name()))
                .mapEmpty();
    }

    /**
     * Builds a minimal {@link TimerRecord} with status {@link TimerStatus#SCHEDULED} for use in
     * tests.
     *
     * @param timerId    the timer id
     * @param workflowId the owning workflow instance id
     * @param fireAt     the scheduled fire time
     * @return a fully populated {@link TimerRecord}
     */
    private static TimerRecord scheduledRecord(UUID timerId, WorkflowInstanceId workflowId, Instant fireAt) {
        return new TimerRecord(
                timerId,
                workflowId,
                "timer-step",
                fireAt,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null, // branchTokenId
                null, // forkStepId
                null,
                DurableMetadata.empty()); // branchId
    }

    // =========================================================================
    // insertScheduled + lockForFiring round-trip
    // =========================================================================

    @Test
    @DisplayName("insertScheduled round-trips with lockForFiring: all fields preserved")
    void insertScheduledAndLockForFiringRoundTrip(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        Instant fireAt = Instant.now().plusSeconds(60);
        TimerRecord record = scheduledRecord(timerId, workflowId, fireAt);

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "lockForFiring must return the inserted record");
                    TimerRecord stored = opt.get();
                    assertEquals(timerId, stored.timerId());
                    assertEquals(workflowId, stored.workflowId());
                    assertEquals("timer-step", stored.stepId());
                    assertEquals(TimerStatus.SCHEDULED, stored.status());
                    assertEquals(record.delayedJobExecutionId(), stored.delayedJobExecutionId());
                    assertNull(stored.firedAt());
                    assertNull(stored.cancelledAt());
                    assertNull(stored.failedAt());
                    assertNull(stored.failureReason());
                    // fireAt round-trips within millisecond precision
                    assertEquals(fireAt.toEpochMilli(), stored.fireAt().toEpochMilli());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // lockForFiring — missing timer
    // =========================================================================

    @Test
    @DisplayName("lockForFiring returns empty for a non-existent timer_id")
    void lockForFiringReturnsEmptyForMissingTimer(VertxTestContext ctx) {
        UUID unknownId = UUID.randomUUID();

        pool.withTransaction(tx -> store.lockForFiring(unknownId, tx))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertFalse(opt.isPresent(), "lockForFiring must return empty for unknown timer_id");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // markFired: SCHEDULED → FIRED
    // =========================================================================

    @Test
    @DisplayName("markFired returns APPLIED then LOST_TO_FIRED on second call")
    void markFiredScheduledToFired(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.markFired(timerId, Instant.now(), tx)))
                .compose(firstResult -> {
                    assertEquals(TimerStatusTransition.APPLIED, firstResult, "first markFired must return APPLIED");
                    return pool.withTransaction(tx -> store.markFired(timerId, Instant.now(), tx));
                })
                .onSuccess(secondResult -> ctx.verify(() -> {
                    assertEquals(
                            TimerStatusTransition.LOST_TO_FIRED,
                            secondResult,
                            "second markFired on FIRED row must return LOST_TO_FIRED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // markCancelled: SCHEDULED → CANCELLED
    // =========================================================================

    @Test
    @DisplayName("markCancelled returns APPLIED then markFired returns LOST_TO_CANCELLED")
    void markCancelledScheduledToCancelled(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.markCancelled(timerId, Instant.now(), tx)))
                .compose(firstResult -> {
                    assertEquals(TimerStatusTransition.APPLIED, firstResult, "first markCancelled must return APPLIED");
                    // Attempt to fire after cancel — must see LOST_TO_CANCELLED
                    return pool.withTransaction(tx -> store.markFired(timerId, Instant.now(), tx));
                })
                .onSuccess(secondResult -> ctx.verify(() -> {
                    assertEquals(
                            TimerStatusTransition.LOST_TO_CANCELLED,
                            secondResult,
                            "markFired on CANCELLED row must return LOST_TO_CANCELLED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // markFailed: SCHEDULED → FAILED
    // =========================================================================

    @Test
    @DisplayName("markFailed returns APPLIED and persists failure_reason; markCancelled after returns LOST_TO_FAILED")
    void markFailedScheduledToFailed(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));
        String reason = "something went wrong";

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.markFailed(timerId, Instant.now(), reason, tx)))
                .compose(firstResult -> {
                    assertEquals(TimerStatusTransition.APPLIED, firstResult, "first markFailed must return APPLIED");
                    // Verify failure_reason was persisted via a direct SELECT
                    return pool.preparedQuery("SELECT failure_reason FROM workflow_timers WHERE timer_id = $1")
                            .execute(Tuple.of(timerId))
                            .map(rs -> rs.iterator().next().getString("failure_reason"));
                })
                .compose(persistedReason -> {
                    assertEquals(reason, persistedReason, "failure_reason must be persisted");
                    // Attempt cancel after fail — must see LOST_TO_FAILED
                    return pool.withTransaction(tx -> store.markCancelled(timerId, Instant.now(), tx));
                })
                .onSuccess(secondResult -> ctx.verify(() -> {
                    assertEquals(
                            TimerStatusTransition.LOST_TO_FAILED,
                            secondResult,
                            "markCancelled on FAILED row must return LOST_TO_FAILED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // findRecoverableScheduled
    // =========================================================================

    @Test
    @DisplayName("findRecoverableScheduled returns only SCHEDULED rows past fire_at ordered ASC; limit honored")
    void findRecoverableScheduledFiltersAndOrders(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        Instant past1 = Instant.now().minusSeconds(120);
        Instant past2 = Instant.now().minusSeconds(60);
        Instant future = Instant.now().plusSeconds(3600);

        UUID scheduledPast1 = UUID.randomUUID();
        UUID scheduledPast2 = UUID.randomUUID();
        UUID scheduledFuture = UUID.randomUUID();
        UUID firedId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(
                                scheduledRecord(scheduledPast1, workflowId, past1), tx)
                        .compose(x -> store.insertScheduled(scheduledRecord(scheduledPast2, workflowId, past2), tx))
                        .compose(x -> store.insertScheduled(scheduledRecord(scheduledFuture, workflowId, future), tx))
                        .compose(x -> store.insertScheduled(scheduledRecord(firedId, workflowId, past1), tx))))
                // Mark one past row as FIRED so it should be excluded
                .compose(v -> pool.withTransaction(tx -> store.markFired(firedId, Instant.now(), tx)))
                // Query: cutoff = now (future row excluded), limit = 1 (only earliest returned)
                .compose(v -> pool.withTransaction(tx -> store.findRecoverableScheduled(Instant.now(), 1, tx)))
                .onSuccess(results -> ctx.verify(() -> {
                    assertEquals(1, results.size(), "limit=1 must return only the earliest SCHEDULED row");
                    assertEquals(scheduledPast1, results.get(0).timerId(), "earliest fire_at row must come first");
                    assertEquals(TimerStatus.SCHEDULED, results.get(0).status());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("findRecoverableScheduled excludes FIRED, CANCELLED, and FAILED rows")
    void findRecoverableScheduledExcludesTerminalRows(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        Instant past = Instant.now().minusSeconds(60);

        UUID scheduledId = UUID.randomUUID();
        UUID firedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(
                        tx -> store.insertScheduled(scheduledRecord(scheduledId, workflowId, past), tx)
                                .compose(x -> store.insertScheduled(scheduledRecord(firedId, workflowId, past), tx))
                                .compose(x -> store.insertScheduled(scheduledRecord(cancelledId, workflowId, past), tx))
                                .compose(x -> store.insertScheduled(scheduledRecord(failedId, workflowId, past), tx))))
                .compose(v -> pool.withTransaction(tx -> store.markFired(firedId, Instant.now(), tx)))
                .compose(v -> pool.withTransaction(tx -> store.markCancelled(cancelledId, Instant.now(), tx)))
                .compose(v -> pool.withTransaction(tx -> store.markFailed(failedId, Instant.now(), "oops", tx)))
                .compose(v -> pool.withTransaction(tx -> store.findRecoverableScheduled(Instant.now(), 100, tx)))
                .onSuccess(results -> ctx.verify(() -> {
                    List<UUID> ids = results.stream().map(TimerRecord::timerId).toList();
                    assertEquals(1, ids.size(), "only the SCHEDULED row must be returned");
                    assertEquals(scheduledId, ids.get(0), "the surviving SCHEDULED timer must be returned");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    // =========================================================================
    // updateExecutionId
    // =========================================================================

    @Test
    @DisplayName("updateExecutionId replaces delayed_job_execution_id without changing status")
    void updateExecutionIdReplacesIdWithoutChangingStatus(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));
        UUID newExecutionId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.updateExecutionId(timerId, newExecutionId, tx)))
                .compose(v -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertTrue(opt.isPresent(), "timer must still be present after updateExecutionId");
                    TimerRecord updated = opt.get();
                    assertEquals(newExecutionId, updated.delayedJobExecutionId(), "execution id must be updated");
                    assertEquals(TimerStatus.SCHEDULED, updated.status(), "status must remain SCHEDULED");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("updateExecutionId on a CANCELLED timer fails with WorkflowConflictException — zero-row guard")
    void updateExecutionIdFailsOnNonScheduledTimer(VertxTestContext ctx) {
        // The recovery verticle re-enqueues a fresh delayed_jobs row for an orphaned timer and
        // then calls updateExecutionId(...). If a concurrent cancel transitions the timer out of
        // SCHEDULED first, the UPDATE matches 0 rows and the impl must fail the future so the
        // surrounding tx (including the just-enqueued delayed_jobs row) rolls back. This guards
        // against stranding a delayed job whose timer row will never reach SCHEDULED again.
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));
        UUID newExecutionId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                .compose(v -> pool.withTransaction(tx -> store.markCancelled(timerId, Instant.now(), tx)))
                .compose(v -> pool.withTransaction(tx -> store.updateExecutionId(timerId, newExecutionId, tx)))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "updateExecutionId on a non-SCHEDULED timer must fail");
                    assertInstanceOf(
                            dev.vertique.workflow.exception.WorkflowConflictException.class,
                            ar.cause(),
                            "expected WorkflowConflictException, got: " + ar.cause());
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // CASCADE on parent delete
    // =========================================================================

    @Test
    @DisplayName("CASCADE on parent delete: lockForFiring returns empty after workflow_instances row deleted")
    void cascadeOnParentDeleteRemovesTimerRow(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        TimerRecord record = scheduledRecord(timerId, workflowId, Instant.now().plusSeconds(60));

        seedInstance(workflowId)
                .compose(v -> pool.withTransaction(tx -> store.insertScheduled(record, tx)))
                // Delete the parent row — CASCADE should remove the timer row
                .compose(v -> pool.preparedQuery("DELETE FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(workflowId.value()))
                        .mapEmpty())
                .compose(v -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx)))
                .onSuccess(opt -> ctx.verify(() -> {
                    assertFalse(opt.isPresent(), "timer row must be gone after parent CASCADE delete");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
