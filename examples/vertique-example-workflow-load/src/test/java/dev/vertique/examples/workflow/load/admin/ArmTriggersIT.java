// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.support.ItDatabase;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the admin entry point against a real database: the migrations create one history table per instance and are
 * safe to run again, an arm sets exactly its trigger state, and the quiet-step guard refuses while a session
 * listens.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArmTriggersIT {

    private static ItDatabase db;
    private static ItDatabase unmigrated;
    private static String conf;
    private static String unmigratedConf;

    @BeforeAll
    static void provision() {
        db = new ItDatabase().start();
        unmigrated = new ItDatabase().start();
        conf = new JsonObject().put("db", db.dbSection(null, 2)).encode();
        unmigratedConf =
                new JsonObject().put("db", unmigrated.dbSection(null, 2)).encode();
    }

    @AfterAll
    static void release() {
        db.close();
        unmigrated.close();
    }

    @Test
    @Order(1)
    @DisplayName("migrate creates one history table per instance, and running it again applies nothing")
    void migrateKeepsOneHistoryPerInstance() throws Exception {
        Result first = run("migrate", "--conf", conf);

        assertEquals(0, first.code, first.err);
        for (String instance : new String[] {"workflow", "inbox-outbox", "job"}) {
            assertTrue(first.out.contains("migrated " + instance + " "), first.out);
        }
        assertEquals(
                3,
                db.count("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                        + " AND table_name LIKE 'flyway_schema_history_%'"));
        assertEquals(
                0,
                db.count("SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                        + " AND table_name = 'flyway_schema_history'"),
                "no single shared history table");

        Result second = run("migrate", "--conf", conf);

        assertEquals(0, second.code, second.err);
        assertEquals(3, countOccurrences(second.out, "applied=0"), second.out);
    }

    @Test
    @Order(2)
    @DisplayName("both triggers are enabled after the migrations")
    void triggersStartEnabled() throws Exception {
        Result result = run("triggers", "--conf", conf);

        assertEquals(0, result.code, result.err);
        assertEquals("workflow-trigger=enabled outbox-trigger=enabled", result.out);
    }

    @Test
    @Order(3)
    @DisplayName("each arm sets exactly its trigger state, read back from the catalog")
    void armsSetTheTriggerState() throws Exception {
        assertArm("OFF", "disabled", "disabled", 'D', 'D');
        assertArm("WF-OFF", "disabled", "enabled", 'D', 'O');
        assertArm("OUT-OFF", "enabled", "disabled", 'O', 'D');
        assertArm("ON-MATCHED", "enabled", "enabled", 'O', 'O');
        assertArm("ON", "enabled", "enabled", 'O', 'O');
    }

    @Test
    @Order(4)
    @DisplayName("an arm is refused while a session listens, and the triggers stay as they were")
    void armRefusesWhileASessionListens() throws Exception {
        try (Connection listener = db.connect();
                Statement statement = listener.createStatement()) {
            statement.execute("LISTEN vertique_workflow_commands");

            Result refused = run("arm", "OFF", "--conf", conf);

            assertEquals(1, refused.code);
            assertTrue(refused.err.contains("listening"), refused.err);
            assertEquals("workflow-trigger=enabled outbox-trigger=enabled", run("triggers", "--conf", conf).out);
        }

        // The listener has gone: the same command now succeeds.
        Result allowed = run("arm", "OFF", "--conf", conf);
        assertEquals(0, allowed.code, allowed.err);
        assertEquals("workflow-trigger=disabled outbox-trigger=disabled", allowed.out);
        assertEquals(0, run("arm", "ON", "--conf", conf).code);
    }

    @Test
    @Order(5)
    @DisplayName("an arm before the migrations fails and says so")
    void armBeforeMigrationsFails() {
        Result result = run("arm", "OFF", "--conf", unmigratedConf);

        assertEquals(1, result.code);
        assertTrue(result.err.contains("run the migrations first"), result.err);
    }

    private static void assertArm(String arm, String workflow, String outbox, char workflowState, char outboxState)
            throws Exception {
        Result result = run("arm", arm, "--conf", conf);

        assertEquals(0, result.code, result.err);
        assertEquals("workflow-trigger=" + workflow + " outbox-trigger=" + outbox, result.out);
        assertEquals(workflowState, catalogState("trg_workflow_commands_notify"));
        assertEquals(outboxState, catalogState("transactional_outbox_notify"));
    }

    private static char catalogState(String trigger) throws SQLException {
        return db.strings("SELECT tgenabled FROM pg_trigger WHERE tgname = '" + trigger + "'")
                .get(0)
                .charAt(0);
    }

    private static int countOccurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LoadAdmin.run(args, new PrintStream(out, true), new PrintStream(err, true));
        return new Result(
                code,
                out.toString(StandardCharsets.UTF_8).trim(),
                err.toString(StandardCharsets.UTF_8).trim());
    }

    private record Result(int code, String out, String err) {}
}
