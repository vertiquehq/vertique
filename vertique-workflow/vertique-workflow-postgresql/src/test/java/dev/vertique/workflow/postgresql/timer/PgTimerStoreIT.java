// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.engine.WorkflowTestTables;
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
 * appropriate {@code LOST_TO_*} constant on a second conflicting call; CASCADE delete on the parent
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
        WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_timers, workflow_history, workflow_dedup,"
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

    /**
     * Inserts a {@code TIMER} command in the given state for the timer, the way a segment records it.
     *
     * @param workflowId the owning instance
     * @param seq        the command's position in the instance's queue
     * @param timerId    the timer the command fires
     * @param state      the command's state
     * @return a future of the command's row id
     */
    private io.vertx.core.Future<Long> insertCommand(
            WorkflowInstanceId workflowId, long seq, UUID timerId, String state) {
        return pool.preparedQuery("INSERT INTO workflow_commands (workflow_id, seq, kind, timer_kind, timer_id, state,"
                        + " recorded_at, available_at, fire_at) VALUES ($1, $2, 'TIMER', 'FIRE', $3, $4, now(), now(),"
                        + " now() - interval '1 minute') RETURNING id")
                .execute(Tuple.of(workflowId.value(), seq, timerId, state))
                .map(rows -> rows.iterator().next().getLong("id"));
    }

    /**
     * Builds a scheduled timer that names no delayed-job execution and is linked to the command.
     *
     * @param timerId    the timer id
     * @param workflowId the owning instance
     * @param fireAt     the scheduled fire time
     * @param commandId  the command that fires the timer
     * @return the record
     */
    private static TimerRecord commandRecord(
            UUID timerId, WorkflowInstanceId workflowId, Instant fireAt, long commandId) {
        return new TimerRecord(
                timerId,
                workflowId,
                "timer-step",
                fireAt,
                TimerStatus.SCHEDULED,
                Instant.now(),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                DurableMetadata.empty(),
                commandId);
    }

    private io.vertx.core.Future<String> commandState(long commandId) {
        return pool.preparedQuery("SELECT state FROM workflow_commands WHERE id = $1")
                .execute(Tuple.of(commandId))
                .map(rows -> rows.iterator().next().getString("state"));
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
    // Timers linked to commands
    // =========================================================================

    @Test
    @DisplayName("a timer linked to a command round-trips without a delayed-job execution")
    void commandLinkedTimerRoundTrips(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> insertCommand(workflowId, 1, timerId, "PENDING"))
                .compose(commandId -> pool.withTransaction(tx -> store.insertScheduled(
                                commandRecord(timerId, workflowId, Instant.now().plusSeconds(60), commandId), tx))
                        .map(v -> commandId))
                .compose(commandId -> pool.withTransaction(tx -> store.lockForFiring(timerId, tx))
                        .map(locked -> new Object[] {commandId, locked}))
                .onSuccess(result -> ctx.verify(() -> {
                    @SuppressWarnings("unchecked")
                    java.util.Optional<TimerRecord> locked = (java.util.Optional<TimerRecord>) result[1];
                    assertTrue(locked.isPresent());
                    assertEquals(result[0], locked.get().commandId(), "the link to the command is read back");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markCancelled cancels the command linked to the timer, and leaves a settled command alone")
    void markCancelledCancelsTheLinkedCommand(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID pendingTimer = UUID.randomUUID();
        UUID claimedTimer = UUID.randomUUID();
        UUID settledTimer = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> insertCommand(workflowId, 1, pendingTimer, "PENDING"))
                .compose(pending -> insertCommand(workflowId, 2, claimedTimer, "CLAIMED")
                        .compose(claimed -> insertCommand(workflowId, 3, settledTimer, "COMPLETE")
                                .map(settled -> new long[] {pending, claimed, settled})))
                .compose(commands -> pool.withTransaction(tx -> store.insertScheduled(
                                        commandRecord(pendingTimer, workflowId, Instant.now(), commands[0]), tx)
                                .compose(x -> store.insertScheduled(
                                        commandRecord(claimedTimer, workflowId, Instant.now(), commands[1]), tx))
                                .compose(x -> store.insertScheduled(
                                        commandRecord(settledTimer, workflowId, Instant.now(), commands[2]), tx)))
                        .map(v -> commands))
                .compose(commands -> pool.withTransaction(tx -> store.markCancelled(pendingTimer, Instant.now(), tx))
                        .compose(first ->
                                pool.withTransaction(tx -> store.markCancelled(claimedTimer, Instant.now(), tx)))
                        .compose(second ->
                                pool.withTransaction(tx -> store.markCancelled(settledTimer, Instant.now(), tx)))
                        .compose(third -> commandState(commands[0]))
                        .compose(pendingState -> commandState(commands[1])
                                .compose(claimedState -> commandState(commands[2])
                                        .map(settledState -> List.of(pendingState, claimedState, settledState)))))
                .onSuccess(states -> ctx.verify(() -> {
                    assertEquals(
                            List.of("CANCELLED", "CANCELLED", "COMPLETE"),
                            states,
                            "unsettled commands are cancelled with their timers; a settled one is left as it is");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    @DisplayName("markCancelled of a timer that is not scheduled leaves its command alone")
    void markCancelledOfAFiredTimerLeavesItsCommand(VertxTestContext ctx) {
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();

        seedInstance(workflowId)
                .compose(v -> insertCommand(workflowId, 1, timerId, "CLAIMED"))
                .compose(commandId -> pool.withTransaction(tx -> store.insertScheduled(
                                        commandRecord(timerId, workflowId, Instant.now(), commandId), tx)
                                .compose(x -> store.markFired(timerId, Instant.now(), tx))
                                .compose(x -> store.markCancelled(timerId, Instant.now(), tx)))
                        .compose(transition -> commandState(commandId).map(state -> List.of(transition, state))))
                .onSuccess(result -> ctx.verify(() -> {
                    assertEquals(TimerStatusTransition.LOST_TO_FIRED, result.get(0));
                    assertEquals("CLAIMED", result.get(1), "the command of a fired timer is the firing segment's own");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
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
