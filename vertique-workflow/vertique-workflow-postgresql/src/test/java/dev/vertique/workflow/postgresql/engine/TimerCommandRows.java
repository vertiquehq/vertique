// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and rewrites the timer rows and {@code TIMER} commands of an instance for integration tests that drive timers
 * through the command log. Nothing but fire times and availability is rewritten.
 */
final class TimerCommandRows {

    private TimerCommandRows() {
        // static helpers only
    }

    /** The timers of the instance with the purpose, in the queue order of their commands. */
    static Future<List<UUID>> timerIds(Pool pool, WorkflowInstanceId instance, TimerPurpose purpose) {
        return pool.preparedQuery("SELECT t.timer_id FROM workflow_timers t JOIN workflow_commands c ON c.id ="
                        + " t.command_id WHERE t.workflow_id = $1 AND t.purpose = $2 ORDER BY c.seq")
                .execute(Tuple.of(instance.value(), purpose.name()))
                .map(rows -> {
                    List<UUID> ids = new ArrayList<>();
                    for (Row row : rows) {
                        ids.add(row.getUUID("timer_id"));
                    }
                    return ids;
                });
    }

    /** Every timer of the instance, of every purpose, in the queue order of their commands. */
    static Future<List<UUID>> allTimerIds(Pool pool, WorkflowInstanceId instance) {
        return pool.preparedQuery("SELECT t.timer_id FROM workflow_timers t JOIN workflow_commands c ON c.id ="
                        + " t.command_id WHERE t.workflow_id = $1 ORDER BY c.seq")
                .execute(Tuple.of(instance.value()))
                .map(rows -> {
                    List<UUID> ids = new ArrayList<>();
                    for (Row row : rows) {
                        ids.add(row.getUUID("timer_id"));
                    }
                    return ids;
                });
    }

    /** The only timer of the instance with the purpose. */
    static Future<UUID> onlyTimerId(Pool pool, WorkflowInstanceId instance, TimerPurpose purpose) {
        return timerIds(pool, instance, purpose).map(ids -> {
            if (ids.size() != 1) {
                throw new AssertionError("expected one " + purpose + " timer of " + instance + " but found " + ids);
            }
            return ids.getFirst();
        });
    }

    /** The state of the command that fires the timer. */
    static Future<String> commandState(Pool pool, UUID timerId) {
        return pool.preparedQuery("SELECT c.state FROM workflow_commands c JOIN workflow_timers t ON t.command_id ="
                        + " c.id WHERE t.timer_id = $1")
                .execute(Tuple.of(timerId))
                .map(rows -> rows.iterator().next().getString("state"));
    }

    /** The status of the timer row. */
    static Future<String> status(Pool pool, UUID timerId) {
        return pool.preparedQuery("SELECT status FROM workflow_timers WHERE timer_id = $1")
                .execute(Tuple.of(timerId))
                .map(rows -> rows.iterator().next().getString("status"));
    }

    /** Makes the timer due now: its command's fire time and availability, and its row's fire time, become the clock. */
    static Future<Void> makeDue(Pool pool, UUID timerId) {
        return pool.preparedQuery("UPDATE workflow_commands SET fire_at = clock_timestamp(), available_at ="
                        + " clock_timestamp() WHERE id = (SELECT command_id FROM workflow_timers WHERE timer_id = $1)"
                        + " RETURNING fire_at")
                .execute(Tuple.of(timerId))
                .compose(rows -> {
                    OffsetDateTime at = rows.iterator().next().getOffsetDateTime("fire_at");
                    return pool.preparedQuery("UPDATE workflow_timers SET fire_at = $2 WHERE timer_id = $1")
                            .execute(Tuple.of(timerId, at));
                })
                .mapEmpty();
    }
}
