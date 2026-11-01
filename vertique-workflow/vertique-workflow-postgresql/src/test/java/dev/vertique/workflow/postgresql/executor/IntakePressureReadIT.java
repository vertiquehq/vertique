// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the reading of the intake limit: the bounded count of due commands counts what it should and nothing else,
 * stops at its cap, and reads the partial index in order instead of judging the queue, with or without table statistics
 * and with claimed rows in front of the due ones.
 */
@ExtendWith(DatabaseExtension.class)
class IntakePressureReadIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_intake_pressure_read_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String COMMANDS = "workflow_commands";
    private static final String INSTANCES = "workflow_instances";

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName(
            "counts due pending and deferred commands, and not claimed, parked, dead, settled or future-dated ones")
    void countsOnlyDuePendingAndDeferredCommands() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given commands in every state, due and future-dated, and two due commands of one instance
            Pool pool = fixture.observer();
            insertInstances(pool, 12);
            insertCommands(pool, 1, 3, "PENDING", "now() - interval '1 minute'", false);
            insertCommands(pool, 4, 5, "DEFERRED", "now() - interval '1 minute'", false);
            insertCommands(pool, 6, 6, "CLAIMED", "now() - interval '1 minute'", true);
            insertCommands(pool, 7, 7, "PARKED", "now() - interval '1 minute'", false);
            insertCommands(pool, 8, 8, "DEAD", "now() - interval '1 minute'", false);
            insertCommands(pool, 9, 9, "COMPLETE", "now() - interval '1 minute'", false);
            insertCommands(pool, 10, 11, "PENDING", "now() + interval '1 day'", false);
            insertCommands(pool, 12, 12, "DEFERRED", "now() + interval '1 hour'", false);
            // a second command behind the first of instance 1 is due too: the reading does not judge the head
            await(pool.query("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, state, recorded_at, available_at)"
                            + " SELECT md5('1')::uuid, 2, 'SIGNAL', 'PENDING', now(), now() - interval '30 seconds'")
                    .execute());
            PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());

            // When the due commands are counted with a generous cap
            long counted = await(commands.countDue(1_000));

            // Then three pending, two deferred and the second command of instance 1 are counted, and nothing else
            assertThat(counted).isEqualTo(6);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("is zero for an idle system holding many timers that are not yet due")
    void isZeroWhenOnlyFutureCommandsExist() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given thousands of timers and backed-off commands that become available only later
            Pool pool = fixture.observer();
            int timers = 3_000;
            insertInstances(pool, timers);
            insertCommands(pool, 1, timers, "PENDING", "now() + interval '1 day' + g * interval '1 second'", false);
            PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());

            // When the due commands are counted
            long counted = await(commands.countDue(100));

            // Then none is counted
            assertThat(counted).isZero();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("stops at the cap")
    void stopsAtTheCap() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            Pool pool = fixture.observer();
            insertInstances(pool, 50);
            insertCommands(pool, 1, 50, "PENDING", "now() - interval '1 hour' + g * interval '1 second'", false);
            PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());

            assertThat(await(commands.countDue(7))).isEqualTo(7);
            assertThat(await(commands.countDue(50))).isEqualTo(50);
            assertThat(await(commands.countDue(51))).isEqualTo(50);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("walks the partial index under the limit and reads at most the claimed rows plus the cap")
    void readsThePartialIndexUnderTheLimit() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given due commands far above the cap, claimed commands ahead of them, and statistics that were never
            // gathered
            Pool pool = fixture.observer();
            int due = 4_000;
            int claimed = 12;
            int cap = 25;
            insertInstances(pool, due + claimed);
            insertCommands(pool, 1, claimed, "CLAIMED", "now() - interval '2 hours' + g * interval '1 second'", true);
            insertCommands(
                    pool,
                    claimed + 1,
                    claimed + due,
                    "PENDING",
                    "now() - interval '1 hour' + g * interval '1 second'",
                    false);
            PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());
            String statement = commands.countDueStatement().replace("$1", Integer.toString(cap));

            // When the statement is explained and run, before and after the statistics are gathered
            JsonObject stale = explained(pool, statement);
            await(pool.query("ANALYZE " + COMMANDS).execute());
            JsonObject fresh = explained(pool, statement);

            // Then it reads the partial index below a limit, and the heap rows it reads are the claimed rows it passes
            // over plus the cap
            for (JsonObject plan : List.of(stale, fresh)) {
                JsonObject limit = findLimit(plan);
                assertThat(limit).as("a Limit node over the commands").isNotNull();
                List<JsonObject> scans = new ArrayList<>();
                scansOf(limit, COMMANDS, scans);
                assertThat(scans).hasSize(1);
                assertThat(scans.get(0).getString("Index Name")).isEqualTo("idx_workflow_commands_active_available_at");
                assertThat(rowsRead(scans.get(0)))
                        .as("rows read for %d due commands, %d claimed and a cap of %d", due, claimed, cap)
                        .isLessThanOrEqualTo((long) claimed + cap + 1);
                assertThat(limit.getLong("Actual Rows")).isEqualTo(cap);
            }
        }
    }

    // --- Helpers ---

    private static void insertInstances(Pool pool, int count) {
        await(pool.query("INSERT INTO " + INSTANCES + " (id, definition_id, definition_version, plan_hash, status,"
                        + " current_step_id, state_json) SELECT md5(g::text)::uuid, 'intake-it', 1, repeat('a', 64),"
                        + " 'WAITING', 's', '{}' FROM generate_series(1, " + count + ") g")
                .execute());
    }

    /** Inserts one command of the state for each instance from {@code from} to {@code to}; {@code g} is its number. */
    private static void insertCommands(Pool pool, int from, int to, String state, String availableAt, boolean claimed) {
        await(pool.query("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, state, recorded_at, available_at"
                        + (claimed ? ", lease_until, claim_token" : "")
                        + ") SELECT md5(g::text)::uuid, 1, 'SIGNAL', '" + state + "', now(), " + availableAt
                        + (claimed ? ", now() + interval '10 minutes', gen_random_uuid()" : "")
                        + " FROM generate_series(" + from + ", " + to + ") g")
                .execute());
    }

    private static JsonObject explained(Pool pool, String statement) {
        Row row = await(pool.query("EXPLAIN (ANALYZE, COSTS OFF, FORMAT JSON) " + statement)
                        .execute())
                .iterator()
                .next();
        Object plan = row.getValue(0);
        JsonArray array = plan instanceof JsonArray a ? a : new JsonArray(plan.toString());
        return array.getJsonObject(0).getJsonObject("Plan");
    }

    private static JsonObject findLimit(JsonObject node) {
        if ("Limit".equals(node.getString("Node Type"))) {
            return node;
        }
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                JsonObject found = findLimit(children.getJsonObject(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void scansOf(JsonObject node, String relation, List<JsonObject> into) {
        if (relation.equals(node.getString("Relation Name"))) {
            into.add(node);
        }
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                scansOf(children.getJsonObject(i), relation, into);
            }
        }
    }

    /** The rows a scan read: those it returned and those its filter removed. */
    private static long rowsRead(JsonObject scan) {
        return scan.getLong("Actual Rows") + scan.getLong("Rows Removed by Filter", 0L);
    }
}
