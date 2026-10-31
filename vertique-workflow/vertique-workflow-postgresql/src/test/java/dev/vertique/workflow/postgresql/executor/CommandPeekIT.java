// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.spi.CommandHead;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the executor's peek: it returns the same heads as a statement that judges every runnable command and limits
 * afterwards, in the same order, whatever the limit; and it stops reading once it has enough heads, instead of judging
 * the whole backlog.
 */
@ExtendWith(DatabaseExtension.class)
class CommandPeekIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_command_peek_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String COMMANDS = "workflow_commands";
    private static final String INSTANCES = "workflow_instances";
    private static final String TASKS = "workflow_tasks";

    /** The statement peek replaced: every runnable command is judged and joined before the limit applies. */
    private static final String JUDGE_EVERYTHING_FIRST = "SELECT c.id FROM " + COMMANDS + " c JOIN " + INSTANCES
            + " i ON i.id = c.workflow_id WHERE " + new CommandEligibilitySql(DbSchemas.empty()).claimable("c")
            + " ORDER BY c.available_at, c.id LIMIT $1";

    @Nested
    @DisplayName("result equivalence")
    class Equivalence {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("returns exactly the claimable heads, in order, and the same as judging everything first")
        void returnsTheClaimableHeadsInOrder() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a queue that holds every shape the eligibility rule distinguishes
                Pool pool = fixture.observer();
                Queue queue = new Queue(pool);
                queue.seedEveryShape();
                PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());
                List<String> expected = queue.expectedHeadNames();

                // When the heads are peeked at every limit from one to past the end
                // Then each result is the prefix of the expected heads and what the unbounded-join statement returns
                for (int limit = 1; limit <= expected.size() + 2; limit++) {
                    List<CommandHead> heads = await(commands.peek(limit));
                    List<String> names = queue.namesOf(
                            heads.stream().map(CommandHead::commandId).toList());
                    assertThat(names)
                            .as("limit %d", limit)
                            .isEqualTo(expected.subList(0, Math.min(limit, expected.size())));
                    assertThat(heads.stream().map(CommandHead::commandId).toList())
                            .as("limit %d against the statement that judges everything first", limit)
                            .isEqualTo(judgedFirst(pool, limit));
                }
            }
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("tells an ended instance's head from a live one's, and carries a migration's target")
        void carriesTheInstanceAndMigrationColumns() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a signal of an ended instance and a migration of a live one
                Pool pool = fixture.observer();
                Queue queue = new Queue(pool);
                UUID ended = queue.instance("COMPLETED");
                UUID live = queue.instance("WAITING");
                queue.command("ended-signal", ended, 1, "SIGNAL", null, "PENDING", -100, null, -100, null, null, null);
                queue.command(
                        "migration",
                        live,
                        1,
                        "MIGRATION",
                        null,
                        "PENDING",
                        -90,
                        null,
                        -90,
                        "{\"version\":3,\"scope\":\"tenant-a\"}",
                        null,
                        null);
                PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());

                // When the heads are peeked
                List<CommandHead> heads = await(commands.peek(10));

                // Then the columns the join supplies and the migration columns are those of the rows
                assertThat(heads).hasSize(2);
                CommandHead endedHead = heads.get(0);
                assertThat(endedHead.commandId()).isEqualTo(queue.id("ended-signal"));
                assertThat(endedHead.instanceEnded()).isTrue();
                assertThat(endedHead.workflowId().value()).isEqualTo(ended);
                assertThat(endedHead.definitionId()).isEqualTo("peek-it");
                assertThat(endedHead.definitionVersion()).isEqualTo(1L);
                assertThat(endedHead.migrationVersion()).isNull();
                CommandHead migrationHead = heads.get(1);
                assertThat(migrationHead.instanceEnded()).isFalse();
                assertThat(migrationHead.migrationVersion()).isEqualTo(3L);
                assertThat(migrationHead.migrationScope()).isEqualTo("tenant-a");
            }
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("limits before the join without losing a head: every command has its instance")
        void everyCommandHasItsInstance() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a queue of claimable commands
                Pool pool = fixture.observer();
                Queue queue = new Queue(pool);
                UUID instance = queue.instance("WAITING");
                queue.command("first", instance, 1, "SIGNAL", null, "PENDING", -50, null, -50, null, null, null);

                // When a command is recorded for an instance that does not exist
                // Then the foreign key rejects it, so no command lacks an instance row for the join to drop
                assertThatThrownBy(() -> queue.command(
                                "orphan",
                                UUID.randomUUID(),
                                1,
                                "SIGNAL",
                                null,
                                "PENDING",
                                -60,
                                null,
                                -60,
                                null,
                                null,
                                null))
                        .hasStackTraceContaining("workflow_commands_workflow_id_fkey");

                // And deleting an instance deletes its commands, so none outlives its instance
                await(pool.preparedQuery("DELETE FROM " + INSTANCES + " WHERE id = $1")
                        .execute(Tuple.of(instance)));
                Long left = await(pool.query("SELECT count(*) FROM " + COMMANDS).execute())
                        .iterator()
                        .next()
                        .getLong(0);
                assertThat(left).isZero();
            }
        }
    }

    @Nested
    @DisplayName("cost")
    class Cost {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("stops reading the partial index at the limit instead of judging the whole backlog")
        void stopsReadingAtTheLimit() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a backlog of claimable commands far larger than the limit, planned from fresh statistics and
                // from none at all
                Pool pool = fixture.observer();
                int backlog = 3_000;
                int limit = 8;
                await(pool.query("INSERT INTO " + INSTANCES + " (id, definition_id, definition_version, plan_hash,"
                                + " status, current_step_id, state_json) SELECT md5(g::text)::uuid, 'peek-it', 1,"
                                + " repeat('a', 64), 'WAITING', 's', '{}' FROM generate_series(1, " + backlog + ") g")
                        .execute());
                await(pool.query("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, state, recorded_at,"
                                + " available_at) SELECT md5(g::text)::uuid, 1, 'SIGNAL', 'PENDING', now(),"
                                + " now() - interval '1 hour' + g * interval '1 second' FROM generate_series(1, "
                                + backlog + ") g")
                        .execute());
                PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());
                String peek = commands.peekStatement().replace("$1", Integer.toString(limit));

                // When the statement is explained and run, before and after the statistics are gathered
                JsonObject withoutStatistics = explained(pool, peek);
                await(pool.query("ANALYZE " + COMMANDS).execute());
                await(pool.query("ANALYZE " + INSTANCES).execute());
                JsonObject withStatistics = explained(pool, peek);

                // Then the limit sits below the join and the commands are read through the partial index, and the
                // rows read are about the limit, whatever the size of the backlog
                for (JsonObject plan : List.of(withoutStatistics, withStatistics)) {
                    JsonObject limitNode = findLimitOverCommands(plan);
                    assertThat(limitNode).as("a Limit node over the commands").isNotNull();
                    assertThat(scansOf(limitNode, INSTANCES))
                            .as("instances scanned below the limit")
                            .isEmpty();
                    List<JsonObject> commandScans = scansOf(limitNode, COMMANDS);
                    assertThat(commandScans)
                            .as("scans of workflow_commands below the limit")
                            .hasSize(1);
                    assertThat(commandScans.get(0).getString("Index Name"))
                            .isEqualTo("idx_workflow_commands_active_available_at");
                    assertThat(rowsRead(commandScans.get(0)))
                            .as("rows the commands scan read for a backlog of %d", backlog)
                            .isLessThanOrEqualTo(limit * 4L);
                    // The eligibility sub-select of the blocking check runs once per row judged, so its loop count is
                    // the
                    // number of commands judged
                    assertThat(maxLoopsOfScans(limitNode, COMMANDS))
                            .as("commands judged for a backlog of %d", backlog)
                            .isLessThanOrEqualTo(limit * 4L);
                }
            }
        }

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("walks the in-flight claims and no more of the ones it passes over")
        void walksTheInFlightClaims() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a few commands in flight (claimed under a live lease) in front of a large backlog
                Pool pool = fixture.observer();
                int backlog = 2_000;
                int inFlight = 12;
                await(pool.query("INSERT INTO " + INSTANCES + " (id, definition_id, definition_version, plan_hash,"
                                + " status, current_step_id, state_json) SELECT md5(g::text)::uuid, 'peek-it', 1,"
                                + " repeat('a', 64), 'WAITING', 's', '{}' FROM generate_series(1, "
                                + (backlog + inFlight)
                                + ") g")
                        .execute());
                await(pool.query("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, state, recorded_at,"
                                + " available_at, lease_until, claim_token) SELECT md5(g::text)::uuid, 1, 'SIGNAL',"
                                + " 'CLAIMED', now(), now() - interval '2 hours' + g * interval '1 second',"
                                + " now() + interval '10 minutes', gen_random_uuid() FROM generate_series(1, "
                                + inFlight + ") g")
                        .execute());
                await(pool.query("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, state, recorded_at,"
                                + " available_at) SELECT md5(g::text)::uuid, 1, 'SIGNAL', 'PENDING', now(),"
                                + " now() - interval '1 hour' + g * interval '1 second' FROM generate_series("
                                + (inFlight + 1) + ", " + (backlog + inFlight) + ") g")
                        .execute());
                await(pool.query("ANALYZE " + COMMANDS).execute());
                PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());
                int limit = 8;

                // When the statement is explained and run
                JsonObject plan = explained(pool, commands.peekStatement().replace("$1", Integer.toString(limit)));

                // Then the in-flight claims are read and passed over, and the rows read are those plus the limit
                JsonObject limitNode = findLimitOverCommands(plan);
                assertThat(limitNode).isNotNull();
                List<JsonObject> commandScans = scansOf(limitNode, COMMANDS);
                assertThat(commandScans).hasSize(1);
                assertThat(commandScans.get(0).getString("Index Name"))
                        .isEqualTo("idx_workflow_commands_active_available_at");
                // The incremental sort above the scan reads one row past the limit to see that the last group is
                // complete
                assertThat(commandScans.get(0).getLong("Actual Rows")).isBetween((long) limit, limit + 1L);
                assertThat(commandScans.get(0).getLong("Rows Removed by Filter"))
                        .isEqualTo(inFlight);
            }
        }
    }

    @Nested
    @DisplayName("table lock order")
    class LockOrder {

        @Test
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        @DisplayName("locks the tasks before the instances, after the commands")
        void locksTasksBeforeInstances() {
            try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
                // Given a peek blocked behind an exclusive lock on the tasks, and another behind one on the instances
                Pool pool = fixture.newPool(6);
                PgWorkflowCommandRepository commands = new PgWorkflowCommandRepository(pool, new PgDbExceptionMapper());

                // When each is blocked, then Postgres's view of what the blocked peek holds is read
                List<String> heldWhileWaitingForTasks = lockedWhileBlockedOn(fixture, pool, commands, TASKS);
                List<String> heldWhileWaitingForInstances = lockedWhileBlockedOn(fixture, pool, commands, INSTANCES);

                // Then the peek that waits for the tasks has not yet locked the instances, and the one that waits for
                // the instances has already locked the tasks
                assertThat(heldWhileWaitingForTasks).containsExactly(COMMANDS);
                assertThat(heldWhileWaitingForInstances).containsExactlyInAnyOrder(COMMANDS, TASKS);
            }
        }

        /** Blocks a peek behind an exclusive lock on the table and returns the tables it holds while it waits. */
        private List<String> lockedWhileBlockedOn(
                ExecutorFixture fixture, Pool pool, PgWorkflowCommandRepository commands, String table) {
            var blocker = await(pool.getConnection());
            try {
                await(blocker.query("BEGIN").execute());
                await(blocker.query("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE")
                        .execute());
                var peeked = commands.peek(1);
                ExecutorFixture.awaitCondition(
                        "a peek waiting for " + table, java.time.Duration.ofSeconds(20), () -> waitingFor(pool, table));
                List<String> held = new ArrayList<>();
                for (Row row : await(pool.preparedQuery("SELECT l.relation::regclass::text AS rel FROM pg_locks l"
                                + " WHERE l.locktype = 'relation' AND l.granted AND l.pid = (SELECT w.pid FROM pg_locks w"
                                + " WHERE w.locktype = 'relation' AND NOT w.granted AND w.relation = $1::regclass)"
                                + " AND l.relation::regclass::text IN ('" + COMMANDS + "', '" + INSTANCES + "', '"
                                + TASKS
                                + "') ORDER BY 1")
                        .execute(Tuple.of(table)))) {
                    held.add(row.getString("rel"));
                }
                await(blocker.query("ROLLBACK").execute());
                await(peeked);
                return held;
            } finally {
                await(blocker.close());
            }
        }

        private boolean waitingFor(Pool pool, String table) {
            return await(pool.preparedQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'relation' AND NOT granted"
                                            + " AND relation = $1::regclass")
                                    .execute(Tuple.of(table)))
                            .iterator()
                            .next()
                            .getLong(0)
                    > 0;
        }
    }

    // --- Helpers ---

    private static List<Long> judgedFirst(Pool pool, int limit) {
        List<Long> ids = new ArrayList<>();
        for (Row row : await(pool.preparedQuery(JUDGE_EVERYTHING_FIRST).execute(Tuple.of(limit)))) {
            ids.add(row.getLong("id"));
        }
        return ids;
    }

    /** The plan of the statement, run for real, as the JSON root of its plan tree. */
    private static JsonObject explained(Pool pool, String statement) {
        Row row = await(pool.query("EXPLAIN (ANALYZE, COSTS OFF, FORMAT JSON) " + statement)
                        .execute())
                .iterator()
                .next();
        Object plan = row.getValue(0);
        JsonArray array = plan instanceof JsonArray a ? a : new JsonArray(plan.toString());
        return array.getJsonObject(0).getJsonObject("Plan");
    }

    /** The first Limit node whose subtree scans the commands, found from the root. */
    private static JsonObject findLimitOverCommands(JsonObject node) {
        if ("Limit".equals(node.getString("Node Type"))
                && !scansOf(node, COMMANDS).isEmpty()) {
            return node;
        }
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                JsonObject found = findLimitOverCommands(children.getJsonObject(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The rows a scan read: those it returned and those its filter removed. */
    private static long rowsRead(JsonObject scan) {
        return scan.getLong("Actual Rows") + scan.getLong("Rows Removed by Filter", 0L);
    }

    /** Every node of the subtree that scans the relation. */
    private static List<JsonObject> scansOf(JsonObject node, String relation) {
        List<JsonObject> scans = new ArrayList<>();
        collect(node, relation, scans);
        return scans;
    }

    private static void collect(JsonObject node, String relation, List<JsonObject> into) {
        if (relation.equals(node.getString("Relation Name")) && "c".equals(node.getString("Alias"))) {
            into.add(node);
        }
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                collect(children.getJsonObject(i), relation, into);
            }
        }
    }

    /** The largest number of times any scan of the relation under the node ran, by any alias. */
    private static long maxLoopsOfScans(JsonObject node, String relation) {
        long max = relation.equals(node.getString("Relation Name")) ? node.getLong("Actual Loops", 0L) : 0L;
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                max = Math.max(max, maxLoopsOfScans(children.getJsonObject(i), relation));
            }
        }
        return max;
    }

    /** Commands seeded by name, so that a result can be read as names. */
    private static final class Queue {
        private final Pool pool;
        private final Map<String, Long> ids = new LinkedHashMap<>();
        private final OffsetDateTime now = OffsetDateTime.now();

        Queue(Pool pool) {
            this.pool = pool;
        }

        UUID instance(String status) {
            UUID id = UUID.randomUUID();
            await(pool.preparedQuery("INSERT INTO " + INSTANCES + " (id, definition_id, definition_version, plan_hash,"
                            + " status, current_step_id, state_json) VALUES ($1, 'peek-it', 1, repeat('a', 64), $2, 's',"
                            + " '{}')")
                    .execute(Tuple.of(id, status)));
            return id;
        }

        long id(String name) {
            return ids.get(name);
        }

        List<String> namesOf(List<Long> commandIds) {
            Map<Long, String> byId = new LinkedHashMap<>();
            ids.forEach((name, id) -> byId.put(id, name));
            return commandIds.stream().map(byId::get).toList();
        }

        /**
         * Records a command. Times are seconds from now; a null time is absent.
         *
         * @param availableAt  when the command becomes available
         * @param fireAt       when a timer fires
         * @param recordedAt   when the command was recorded
         * @param leaseUntil   when a claim's lease ends
         */
        void command(
                String name,
                UUID workflow,
                long seq,
                String kind,
                String timerKind,
                String state,
                long availableAt,
                Long fireAt,
                long recordedAt,
                String targetJson,
                UUID taskId,
                Long leaseUntil) {
            Row row = await(pool.preparedQuery("INSERT INTO " + COMMANDS + " (workflow_id, seq, kind, timer_kind,"
                                    + " state, recorded_at, available_at, fire_at, target_json, task_id, lease_until,"
                                    + " claim_token) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10, $11, $12)"
                                    + " RETURNING id")
                            .execute(Tuple.tuple()
                                    .addUUID(workflow)
                                    .addLong(seq)
                                    .addString(kind)
                                    .addString(timerKind)
                                    .addString(state)
                                    .addOffsetDateTime(now.plusSeconds(recordedAt))
                                    .addOffsetDateTime(now.plusSeconds(availableAt))
                                    .addValue(fireAt == null ? null : now.plusSeconds(fireAt))
                                    .addValue(targetJson == null ? null : new JsonObject(targetJson))
                                    .addValue(taskId)
                                    .addValue(leaseUntil == null ? null : now.plusSeconds(leaseUntil))
                                    .addValue("CLAIMED".equals(state) ? UUID.randomUUID() : null)))
                    .iterator()
                    .next();
            ids.put(name, row.getLong("id"));
        }

        void task(UUID workflow, UUID taskId, String status) {
            await(pool.preparedQuery(
                            "INSERT INTO " + TASKS + " (task_id, workflow_id, step_id, status, assignee_type,"
                                    + " assignee_key, decisions_snapshot_json) VALUES ($1, $2, 'approve', $3, 'USER', 'u', '[]')")
                    .execute(Tuple.of(taskId, workflow, status)));
        }

        private final List<String> expected = new ArrayList<>();

        List<String> expectedHeadNames() {
            return List.copyOf(expected);
        }

        /** Records, for each way a command can be or not be a head, a command or two, and the heads in order. */
        void seedEveryShape() {
            // Seconds before now; the order of the available times is the order of the expected heads. Within the
            // block, each name carries its position, and the expected list is built from the eligible ones.
            // A plain pending head, then two heads that became available at the very same instant: the id breaks the
            // tie.
            UUID plain = instance("WAITING");
            command("plain", plain, 1, "SIGNAL", null, "PENDING", -1000, null, -1000, null, null, null);
            expected.add("plain");
            UUID tieA = instance("WAITING");
            UUID tieB = instance("WAITING");
            command("tie-a", tieA, 1, "SIGNAL", null, "PENDING", -990, null, -990, null, null, null);
            command("tie-b", tieB, 1, "SIGNAL", null, "PENDING", -990, null, -990, null, null, null);
            expected.add("tie-a");
            expected.add("tie-b");

            // A deferred command that is due is a head; one that is not due is not.
            UUID deferredDue = instance("WAITING");
            command("deferred-due", deferredDue, 1, "SIGNAL", null, "DEFERRED", -980, null, -1100, null, null, null);
            expected.add("deferred-due");
            UUID deferredLater = instance("WAITING");
            command("deferred-later", deferredLater, 1, "SIGNAL", null, "DEFERRED", 600, null, -1100, null, null, null);

            // A claim under a live lease is in flight and keeps the commands behind it back; one whose lease ended is
            // a head again.
            UUID inFlight = instance("WAITING");
            command("in-flight", inFlight, 1, "SIGNAL", null, "CLAIMED", -970, null, -970, null, null, 300L);
            command("behind-in-flight", inFlight, 2, "SIGNAL", null, "PENDING", -960, null, -960, null, null, null);
            UUID expiredLease = instance("WAITING");
            command("expired-lease", expiredLease, 1, "SIGNAL", null, "CLAIMED", -950, null, -950, null, null, -5L);
            expected.add("expired-lease");

            // A command behind an unsettled earlier one is not a head; behind a settled, parked or dead one it is.
            UUID queued = instance("WAITING");
            command("queued-first", queued, 1, "SIGNAL", null, "PENDING", -940, null, -940, null, null, null);
            command("queued-second", queued, 2, "SIGNAL", null, "PENDING", -930, null, -930, null, null, null);
            expected.add("queued-first");
            UUID behindDead = instance("WAITING");
            command("dead", behindDead, 1, "SIGNAL", null, "DEAD", -920, null, -920, null, null, null);
            command("behind-dead", behindDead, 2, "SIGNAL", null, "PENDING", -910, null, -910, null, null, null);
            expected.add("behind-dead");
            UUID behindParked = instance("WAITING");
            command("parked", behindParked, 1, "SIGNAL", null, "PARKED", -900, null, -900, null, null, null);
            command("behind-parked", behindParked, 2, "SIGNAL", null, "PENDING", -890, null, -890, null, null, null);
            expected.add("behind-parked");
            UUID behindComplete = instance("WAITING");
            command("complete", behindComplete, 1, "SIGNAL", null, "COMPLETE", -880, null, -880, null, null, null);
            command(
                    "behind-complete",
                    behindComplete,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -870,
                    null,
                    -870,
                    null,
                    null,
                    null);
            expected.add("behind-complete");

            // A timer that has not fired does not keep the commands behind it back, and is not a head itself.
            UUID unfired = instance("WAITING");
            command("unfired-timer", unfired, 1, "TIMER", "FIRE", "PENDING", 600, 600L, -860, null, null, null);
            command("behind-unfired-timer", unfired, 2, "SIGNAL", null, "PENDING", -850, null, -850, null, null, null);
            expected.add("behind-unfired-timer");
            // A timer that has fired is a head and keeps the commands behind it back.
            UUID fired = instance("WAITING");
            command("fired-timer", fired, 1, "TIMER", "FIRE", "PENDING", -840, -840L, -860, null, null, null);
            command("behind-fired-timer", fired, 2, "SIGNAL", null, "PENDING", -830, null, -830, null, null, null);
            expected.add("fired-timer");

            // A timeout is held back by a signal recorded before its fire time that its wait accepts, and the signal
            // then is a head although the timeout is earlier in the queue; one the wait does not accept holds nothing.
            long fire = -820;
            UUID heldByName = instance("WAITING");
            command(
                    "timeout-held",
                    heldByName,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -820,
                    fire,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    null,
                    null);
            command(
                    "signal-holds-timeout",
                    heldByName,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -815,
                    null,
                    -830,
                    "{\"name\":\"go\"}",
                    null,
                    null);
            expected.add("signal-holds-timeout");
            UUID otherName = instance("WAITING");
            command(
                    "timeout-other-name",
                    otherName,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -810,
                    -810L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    null,
                    null);
            command(
                    "signal-other-name",
                    otherName,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -805,
                    null,
                    -830,
                    "{\"name\":\"stop\"}",
                    null,
                    null);
            expected.add("timeout-other-name");
            UUID otherBranch = instance("WAITING");
            command(
                    "timeout-branch",
                    otherBranch,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -800,
                    -800L,
                    -1000,
                    "{\"signalNames\":[\"go\"],\"branchId\":\"b1\"}",
                    null,
                    null);
            command(
                    "signal-other-branch",
                    otherBranch,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -795,
                    null,
                    -830,
                    "{\"name\":\"go\",\"branchId\":\"b2\"}",
                    null,
                    null);
            expected.add("timeout-branch");
            UUID signalAfterFire = instance("WAITING");
            command(
                    "timeout-fired-first",
                    signalAfterFire,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -790,
                    -790L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    null,
                    null);
            command(
                    "signal-after-fire",
                    signalAfterFire,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -785,
                    null,
                    -700,
                    "{\"name\":\"go\"}",
                    null,
                    null);
            expected.add("timeout-fired-first");

            // A timeout that names no wait is held back by any earlier-recorded signal or completion of its instance.
            UUID noTarget = instance("WAITING");
            command(
                    "timeout-no-target",
                    noTarget,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -780,
                    -780L,
                    -1000,
                    null,
                    null,
                    null);
            command(
                    "signal-holds-untargeted",
                    noTarget,
                    2,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -775,
                    null,
                    -830,
                    "{\"name\":\"x\"}",
                    null,
                    null);
            expected.add("signal-holds-untargeted");

            // A completion holds back the timeout of the task it reserves, and no other.
            UUID taskA = UUID.randomUUID();
            UUID heldByCompletion = instance("WAITING");
            command(
                    "timeout-task",
                    heldByCompletion,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -770,
                    -770L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    taskA,
                    null);
            command(
                    "completion-holds-timeout",
                    heldByCompletion,
                    2,
                    "COMPLETION",
                    null,
                    "PENDING",
                    -765,
                    null,
                    -830,
                    null,
                    taskA,
                    null);
            expected.add("completion-holds-timeout");
            UUID otherTask = instance("WAITING");
            command(
                    "timeout-other-task",
                    otherTask,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -760,
                    -760L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    UUID.randomUUID(),
                    null);
            command(
                    "completion-other-task",
                    otherTask,
                    2,
                    "COMPLETION",
                    null,
                    "PENDING",
                    -755,
                    null,
                    -830,
                    null,
                    UUID.randomUUID(),
                    null);
            expected.add("timeout-other-task");

            // A signal held in flight holds back the timeout it targets, and a dead one does not.
            UUID inFlightSignal = instance("WAITING");
            command(
                    "timeout-behind-claimed-signal",
                    inFlightSignal,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -750,
                    -750L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    null,
                    null);
            command(
                    "claimed-signal",
                    inFlightSignal,
                    2,
                    "SIGNAL",
                    null,
                    "CLAIMED",
                    -745,
                    null,
                    -830,
                    "{\"name\":\"go\"}",
                    null,
                    300L);
            UUID deadSignal = instance("WAITING");
            command(
                    "timeout-behind-dead-signal",
                    deadSignal,
                    1,
                    "TIMER",
                    "TIMEOUT",
                    "PENDING",
                    -740,
                    -740L,
                    -1000,
                    "{\"signalNames\":[\"go\"]}",
                    null,
                    null);
            command(
                    "dead-signal",
                    deadSignal,
                    2,
                    "SIGNAL",
                    null,
                    "DEAD",
                    -735,
                    null,
                    -830,
                    "{\"name\":\"go\"}",
                    null,
                    null);
            expected.add("timeout-behind-dead-signal");

            // A due-date or reminder timer is held back while its task is completing, and the command behind it is a
            // head then; while the task is open the timer is the head.
            UUID completingTask = UUID.randomUUID();
            UUID completing = instance("WAITING");
            task(completing, completingTask, "COMPLETING");
            command(
                    "due-timer-completing",
                    completing,
                    1,
                    "TIMER",
                    "DUE",
                    "PENDING",
                    -730,
                    -730L,
                    -1000,
                    null,
                    completingTask,
                    null);
            command(
                    "reminder-timer-completing",
                    completing,
                    2,
                    "TIMER",
                    "REMINDER",
                    "PENDING",
                    -725,
                    -725L,
                    -1000,
                    null,
                    completingTask,
                    null);
            command(
                    "behind-completing-timers",
                    completing,
                    3,
                    "SIGNAL",
                    null,
                    "PENDING",
                    -720,
                    null,
                    -720,
                    null,
                    null,
                    null);
            expected.add("behind-completing-timers");
            UUID openTask = UUID.randomUUID();
            UUID open = instance("WAITING");
            task(open, openTask, "OPEN");
            command("due-timer-open", open, 1, "TIMER", "DUE", "PENDING", -710, -710L, -1000, null, openTask, null);
            command("behind-open-task-timer", open, 2, "SIGNAL", null, "PENDING", -705, null, -705, null, null, null);
            expected.add("due-timer-open");

            // An ended instance's head is still a head: the claim settles it.
            UUID endedInstance = instance("CANCELLED");
            command("ended-instance", endedInstance, 1, "SIGNAL", null, "PENDING", -700, null, -700, null, null, null);
            expected.add("ended-instance");

            // A command that is not yet available is not a head.
            UUID future = instance("WAITING");
            command("not-yet", future, 1, "SIGNAL", null, "PENDING", 60, null, -690, null, null, null);
        }
    }
}
