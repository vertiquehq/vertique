// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.examples.workflow.load.config.LoadArm;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the parts of the admin entry point that need no database. */
class LoadAdminTest {

    @Test
    @DisplayName("a migration forces migrate mode and named instances and keeps every other setting")
    void migrationForcesModeAndNames() {
        JsonObject config = new JsonObject()
                .put("db", new JsonObject().put("host", "h"))
                .put("flyway", new JsonObject().put("mode", "DISABLED").put("lockRetryCount", 7));

        JsonObject forced = LoadAdmin.forMigration(config);

        assertEquals("MIGRATE", forced.getJsonObject("flyway").getString("mode"));
        assertTrue(forced.getJsonObject("flyway").getBoolean("named"));
        assertTrue(forced.getJsonObject("flyway").getBoolean("baselineOnMigrate"));
        assertEquals("0", forced.getJsonObject("flyway").getString("baselineVersion"));
        assertEquals(7, forced.getJsonObject("flyway").getInteger("lockRetryCount"));
        assertEquals("h", forced.getJsonObject("db").getString("host"));
        assertEquals("DISABLED", config.getJsonObject("flyway").getString("mode"), "the input is not changed");
    }

    @Test
    @DisplayName("a configuration that sets the baseline keeps its own choice")
    void migrationKeepsAnExplicitBaseline() {
        JsonObject config = new JsonObject().put("flyway", new JsonObject().put("baselineOnMigrate", false));

        JsonObject forced = LoadAdmin.forMigration(config);

        assertFalse(forced.getJsonObject("flyway").getBoolean("baselineOnMigrate"));
        assertFalse(forced.getJsonObject("flyway").containsKey("baselineVersion"));
    }

    @Test
    @DisplayName("a migration works on a configuration without a flyway section")
    void migrationAddsTheFlywaySection() {
        JsonObject forced = LoadAdmin.forMigration(new JsonObject());

        assertEquals("MIGRATE", forced.getJsonObject("flyway").getString("mode"));
        assertTrue(forced.getJsonObject("flyway").getBoolean("named"));
    }

    @Test
    @DisplayName("the admin connection names itself and follows the SSL mode")
    void jdbcUrl() {
        DbPoolConfig plain = DbPoolConfig.builder()
                .host("db.local")
                .port(5433)
                .database("load")
                .user("u")
                .build();
        DbPoolConfig defaultPort = DbPoolConfig.builder()
                .host("db.local")
                .database("load")
                .user("u")
                .sslMode("REQUIRE")
                .build();

        assertEquals(
                "jdbc:postgresql://db.local:5433/load?ApplicationName=load-admin&sslmode=disable",
                LoadAdmin.jdbcUrl(plain));
        assertEquals(
                "jdbc:postgresql://db.local:5432/load?ApplicationName=load-admin&sslmode=require",
                LoadAdmin.jdbcUrl(defaultPort));
    }

    @Test
    @DisplayName("arm-config prints the overlay of the arm and exits 0 without a database")
    void armConfigPrintsTheOverlay() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = LoadAdmin.run(new String[] {"arm-config", "out-off"}, new PrintStream(out), new PrintStream(err));

        assertEquals(0, code);
        assertEquals(LoadArm.OUT_OFF.configOverlay(), new JsonObject(text(out)));
        assertEquals("", text(err));
    }

    @Test
    @DisplayName("a wrong command line exits 2 and prints the usage")
    void wrongCommandLineExitsTwo() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = LoadAdmin.run(new String[] {"arm", "half"}, new PrintStream(out), new PrintStream(err));

        assertEquals(2, code);
        assertEquals("", text(out));
        assertFalse(text(err).isBlank());
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8).trim();
    }
}
