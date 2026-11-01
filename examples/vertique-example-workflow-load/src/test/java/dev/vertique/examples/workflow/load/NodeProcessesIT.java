// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.support.ChildJvm;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import dev.vertique.examples.workflow.load.support.NodeConfigs;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs two nodes as separate JVM processes, started through the launcher, against one database that a separate admin
 * process migrated, and checks what a load run depends on: instances complete with commands claimed by both nodes,
 * each instance records three commands and one dispatch, the connections carry the node names, and the migrations
 * keep one history table each.
 *
 * <p>Node 1 runs the handler with a 20 ms delay and node 2 with none, so both paths of the handler serve dispatches.
 * The processes, the container start and the migration happen in {@code @BeforeAll}; the tests themselves only drive
 * load and read the database.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NodeProcessesIT {

    private static final String HOST = "127.0.0.1";
    private static final String LAUNCHER = "dev.vertique.launcher.VertiqueApplication";
    private static final String ADMIN = "dev.vertique.examples.workflow.load.admin.LoadAdmin";
    private static final int BATCH = 8;
    private static final int MAX_INSTANCES = 120;

    @TempDir
    static Path work;

    private static ItDatabase db;
    private static Vertx vertx;
    private static WebClient client;
    private static final ChildJvm[] nodes = new ChildJvm[2];
    private static final int[] ports = new int[2];
    private static String migrationOutput;
    private static int started;

    @BeforeAll
    static void startCluster() throws Exception {
        db = new ItDatabase().start();
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);

        JsonObject adminConfig = new JsonObject().put("db", db.dbSection("load-admin-it", 8));
        try (ChildJvm migrate = admin("migrate-1", "migrate", "--conf", adminConfig.encode())) {
            assertEquals(0, migrate.waitFor(Duration.ofSeconds(60)), migrate.output());
            migrationOutput = migrate.output();
        }

        for (int i = 0; i < nodes.length; i++) {
            Path portFile = work.resolve("node-" + (i + 1) + ".port");
            // Node 2 runs a zero handler delay, so both the timer path and the no-timer path serve dispatches.
            JsonObject overlay = NodeConfigs.fastNode(db, "load-it-node-" + (i + 1), portFile, i == 0 ? 20 : 0);
            nodes[i] = ChildJvm.start(
                    "node-" + (i + 1),
                    work.resolve("node-" + (i + 1) + ".log"),
                    Path.of("").toAbsolutePath(),
                    Map.of(
                            "VERTX_CONFIG_LOCATIONS",
                            Path.of("config").toAbsolutePath().toString()),
                    LAUNCHER,
                    List.of("--conf", overlay.encode()));
        }
        for (int i = 0; i < nodes.length; i++) {
            ports[i] = awaitReady(i);
        }
        driveLoad();
    }

    /**
     * Offers starts in small batches, alternately to the two ingresses, until the commands have been claimed by
     * both executors and the dispatches delivered by both relays, then waits for every started instance to complete.
     * Which node wins a claim is a race, so the load is topped up instead of assuming a split.
     */
    private static void driveLoad() throws Exception {
        while (started < MAX_INSTANCES && !bothNodesWorked()) {
            for (int i = 0; i < BATCH; i++, started++) {
                HttpResponse<Buffer> response = post(started % 2, "/start?key=smoke-" + started);
                assertEquals(200, response.statusCode(), response.bodyAsString());
                assertEquals("accepted", response.bodyAsJsonObject().getString("outcome"));
            }
            Thread.sleep(200);
        }
        long completed =
                db.awaitCount("SELECT count(*) FROM workflow_instances WHERE status = 'COMPLETED'", started, 20_000);
        assertEquals(started, completed, diagnostics());
    }

    private static boolean bothNodesWorked() throws Exception {
        return claimers("workflow_commands") >= 2 && claimers("outbox") >= 2;
    }

    private static long claimers(String table) throws Exception {
        return db.count("SELECT count(DISTINCT regexp_replace(claimed_by, '-[0-9]+$', '')) FROM " + table
                + " WHERE claimed_by IS NOT NULL");
    }

    @AfterAll
    static void stopCluster() throws Exception {
        for (ChildJvm node : nodes) {
            if (node != null) {
                node.close();
            }
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("two node processes complete instances, and both claim commands and deliver dispatches")
    void twoProcessesShareTheWork() throws Exception {
        assertTrue(started >= BATCH, "load was offered");
        assertEquals(
                started,
                db.count("SELECT count(*) FROM workflow_instances WHERE status = 'COMPLETED'"),
                "every instance completed");
        assertEquals(2, claimers("workflow_commands"), "commands were claimed by both nodes; " + diagnostics());
        assertEquals(2, claimers("outbox"), "dispatches were delivered by both nodes; " + diagnostics());
    }

    @Test
    @DisplayName("each instance records three commands and one dispatch")
    void workloadShape() throws Exception {
        assertEquals(
                0,
                db.count("SELECT count(*) FROM (SELECT workflow_id FROM workflow_commands GROUP BY workflow_id"
                        + " HAVING count(*) <> 3 OR count(*) FILTER (WHERE kind = 'START') <> 1"
                        + " OR count(*) FILTER (WHERE kind = 'SIGNAL') <> 1"
                        + " OR count(*) FILTER (WHERE kind = 'TIMER') <> 1) odd"),
                "every completed instance holds exactly one START, one SIGNAL and one TIMER command");
        assertEquals(
                0,
                db.count("SELECT count(*) FROM workflow_commands WHERE state <> 'COMPLETE'"),
                "every command completed");
        assertEquals(
                0,
                db.count("SELECT count(*) FROM (SELECT aggregate_id FROM outbox GROUP BY aggregate_id"
                        + " HAVING count(*) <> 1) odd"),
                "every instance created exactly one outbox row");
        assertEquals(
                0,
                db.count("SELECT count(*) FROM outbox WHERE destination <> 'load.work' OR event_type <> 'work'"
                        + " OR state <> 'PUBLISHED' OR attempt <> 0 OR last_error IS NOT NULL"),
                "the only outbox rows are the dispatches, published at the first attempt");
        assertEquals(
                db.count("SELECT count(*) FROM workflow_instances"),
                db.count("SELECT count(*) FROM outbox"),
                "one dispatch per instance");
        assertEquals(
                0,
                db.count(
                        "SELECT count(*) FROM workflow_dedup WHERE kind = 'signal' AND key NOT LIKE 'load-callback:%'"),
                "every signal carries the deterministic callback key");
    }

    @Test
    @DisplayName("each node's connections carry its own application name")
    void connectionsCarryNodeNames() throws Exception {
        List<String> names = db.strings("SELECT DISTINCT application_name FROM pg_stat_activity"
                + " WHERE datname = current_database() AND application_name LIKE 'load-it-node-%' ORDER BY 1");

        assertEquals(List.of("load-it-node-1", "load-it-node-2"), names);
        for (int i = 0; i < nodes.length; i++) {
            JsonObject effective = get(i, "/effective").bodyAsJsonObject();
            assertEquals(
                    "load-it-node-" + (i + 1), effective.getJsonObject("db").getString("applicationName"));
            assertEquals("DISABLED", effective.getJsonObject("flyway").getString("mode"));
            assertEquals(0, effective.getJsonObject("observers").getInteger("executor"));
            assertEquals(0, effective.getJsonObject("observers").getInteger("outboxPublish"));
        }
    }

    @Test
    @DisplayName("the migrations ran once, each with its own history table, and the nodes did not migrate")
    void migrationsKeepSeparateHistories() throws Exception {
        List<String> histories = db.strings("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name LIKE 'flyway_schema_history%' ORDER BY 1");

        assertEquals(
                List.of(
                        "flyway_schema_history_inbox_outbox",
                        "flyway_schema_history_job",
                        "flyway_schema_history_workflow"),
                histories);
        for (String instance : List.of("workflow", "inbox-outbox", "job")) {
            assertTrue(migrationOutput.contains("migrated " + instance + " "), migrationOutput);
        }
        assertEquals(
                0,
                db.count("SELECT count(*) FROM \"flyway_schema_history_workflow\" WHERE installed_by IS NULL"),
                "history is readable");
        assertEquals(
                db.count("SELECT count(*) FROM \"flyway_schema_history_workflow\""),
                db.count("SELECT count(*) FROM \"flyway_schema_history_workflow\" WHERE success"),
                "every workflow migration succeeded");
    }

    @Test
    @DisplayName("the arm command refuses to change the triggers while the nodes listen")
    void armRefusesWhileNodesListen() throws Exception {
        JsonObject adminConfig = new JsonObject().put("db", db.dbSection("load-admin-it", 8));
        try (ChildJvm arm = admin("arm-1", "arm", "OFF", "--conf", adminConfig.encode())) {
            int exit = arm.waitFor(Duration.ofSeconds(15));

            assertEquals(1, exit, arm.output());
            assertTrue(arm.output().contains("listening"), arm.output());
        }
        try (ChildJvm triggers = admin("triggers-1", "triggers", "--conf", adminConfig.encode())) {
            assertEquals(0, triggers.waitFor(Duration.ofSeconds(15)), triggers.output());
            assertTrue(
                    triggers.output().contains("workflow-trigger=enabled outbox-trigger=enabled"), triggers.output());
        }
    }

    // --- Helpers ---

    private static ChildJvm admin(String name, String... arguments) throws Exception {
        return ChildJvm.start(
                name,
                work.resolve(name + ".log"),
                Path.of("").toAbsolutePath(),
                Map.of(
                        "VERTX_CONFIG_LOCATIONS",
                        Path.of("config").toAbsolutePath().toString()),
                ADMIN,
                List.of(arguments));
    }

    private static int awaitReady(int node) throws Exception {
        Path portFile = work.resolve("node-" + (node + 1) + ".port");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!nodes[node].isAlive()) {
                throw new IllegalStateException("node " + (node + 1) + " exited:\n" + nodes[node].output());
            }
            if (Files.exists(portFile)) {
                int port = Integer.parseInt(Files.readString(portFile));
                try {
                    HttpResponse<Buffer> ready =
                            await(client.get(port, HOST, "/ready").send());
                    if (ready.statusCode() == 200) {
                        return port;
                    }
                } catch (Exception notYet) {
                    // The port file is written once the server listens; a refused connection means a short race.
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("node " + (node + 1) + " not ready:\n" + nodes[node].output());
    }

    private static HttpResponse<Buffer> post(int node, String uri) throws Exception {
        return await(client.post(ports[node], HOST, uri).send());
    }

    private static HttpResponse<Buffer> get(int node, String uri) throws Exception {
        return await(client.get(ports[node], HOST, uri).send());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static String diagnostics() {
        return "\n--- node 1 ---\n" + nodes[0].output() + "\n--- node 2 ---\n" + nodes[1].output();
    }
}
