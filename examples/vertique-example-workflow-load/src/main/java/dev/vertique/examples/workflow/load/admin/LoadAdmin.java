// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.admin;

import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.MigrationInstanceResult;
import dev.vertique.db.flyway.MigrationReport;
import dev.vertique.db.flyway.MigrationRunException;
import dev.vertique.examples.workflow.load.config.LoadArm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The admin entry point of the load harness: it runs the migrations once and sets the trigger state of an arm.
 *
 * <p>Both jobs happen in the quiet step of a run, before any node starts. Nodes start with migrations disabled, so
 * nothing else creates the schema.
 *
 * <ul>
 *   <li>{@code migrate} runs the workflow, outbox and job migrations, each as its own Flyway instance with its own
 *       history table (see {@link MigrationComponent}). It forces {@code flyway.mode} to {@code MIGRATE} and
 *       {@code flyway.named} on and takes every other setting from the configuration.</li>
 *   <li>{@code triggers} prints the state of the two notification triggers.</li>
 *   <li>{@code arm <arm>} sets the two triggers to the state of the arm and refuses while a listener is connected.</li>
 *   <li>{@code arm-config <arm>} prints the configuration overlay the arm needs on every node, as JSON.</li>
 * </ul>
 *
 * <p>The configuration is resolved exactly as the node launcher resolves it: the {@code config/} directory, the
 * environment, system properties and, with the highest precedence, {@code --conf}, which takes a JSON object or the
 * path of a JSON file. Exit codes: 0 success, 1 the command failed, 2 the command line is wrong.
 *
 * <pre>{@code
 * java -cp <classpath> dev.vertique.examples.workflow.load.admin.LoadAdmin migrate --conf '{"db":{"port":5433}}'
 * java -cp <classpath> dev.vertique.examples.workflow.load.admin.LoadAdmin arm OFF
 * }</pre>
 */
public final class LoadAdmin {

    private static final long MIGRATION_TIMEOUT_MINUTES = 5;
    private static final String APPLICATION_NAME = "load-admin";

    private LoadAdmin() {}

    /**
     * Runs one admin command and exits the JVM with its exit code.
     *
     * @param args the command line, see the class description
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one admin command.
     *
     * @param args the command line, see the class description
     * @param out where results are printed
     * @param err where failures are printed
     * @return the exit code: 0 on success, 1 when the command failed, 2 when the command line is wrong
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        AdminArguments arguments;
        try {
            arguments = AdminArguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        if (arguments.command() == AdminArguments.Command.ARM_CONFIG) {
            out.println(arguments.arm().configOverlay().encode());
            return 0;
        }
        List<ConfigPropertySource> sources = List.of();
        Vertx vertx = null;
        try {
            BootstrapConfigLoader.BootstrapResult resolved = BootstrapConfigLoader.load(overlay(arguments.conf()));
            sources = resolved.propertySources();
            JsonObject config = resolved.config();
            if (arguments.command() == AdminArguments.Command.MIGRATE) {
                config = forMigration(config);
            }
            vertx = Vertx.vertx();
            MigrationComponent component = DaggerMigrationComponent.factory().create(vertx, config);
            if (arguments.command() == AdminArguments.Command.MIGRATE) {
                return migrate(component, vertx, out);
            }
            return triggers(component.dbPoolConfig(), arguments.arm(), out);
        } catch (MigrationRunException e) {
            err.println("Migration failed: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println(arguments.command().word() + " failed: " + e);
            return 1;
        } finally {
            close(vertx);
            for (int i = sources.size() - 1; i >= 0; i--) {
                sources.get(i).close();
            }
        }
    }

    /**
     * Returns the configuration a migration runs on: the loaded tree with {@code flyway.mode} {@code MIGRATE} and
     * {@code flyway.named} on, and, unless the configuration says otherwise, {@code flyway.baselineOnMigrate} on with
     * baseline version {@code 0}.
     *
     * <p>The three table sets share the default schema. Flyway refuses an instance whose schema already holds the
     * tables of an earlier instance and has no history of its own, unless it may baseline; baseline version 0 lets
     * every script of the instance still run.
     *
     * @param config the resolved configuration tree
     * @return a copy with the Flyway settings set
     */
    static JsonObject forMigration(JsonObject config) {
        JsonObject copy = config.copy();
        JsonObject flyway = copy.getJsonObject("flyway");
        flyway = flyway == null ? new JsonObject() : flyway;
        flyway.put("mode", "MIGRATE").put("named", true);
        if (!flyway.containsKey("baselineOnMigrate")) {
            flyway.put("baselineOnMigrate", true);
            if (!flyway.containsKey("baselineVersion")) {
                flyway.put("baselineVersion", "0");
            }
        }
        return copy.put("flyway", flyway);
    }

    /**
     * Builds the JDBC URL of the admin connection from the typed database configuration.
     *
     * @param db the database configuration
     * @return a URL that names the host, port and database and gives the connection the application name
     *     {@code load-admin}
     */
    static String jdbcUrl(DbPoolConfig db) {
        int port = db.port() > 0 ? db.port() : 5432;
        String sslMode = db.sslMode() == null ? "disable" : db.sslMode().toLowerCase(Locale.ROOT);
        return "jdbc:postgresql://" + db.host() + ":" + port + "/" + db.database() + "?ApplicationName="
                + APPLICATION_NAME + "&sslmode=" + sslMode;
    }

    private static int migrate(MigrationComponent component, Vertx vertx, PrintStream out) throws Exception {
        MigrationReport report = component
                .runner()
                .run(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(MIGRATION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        for (MigrationInstanceResult result : report.instances()) {
            out.println("migrated " + result.instance().name() + " history="
                    + result.instance().table() + " applied="
                    + result.migrationsApplied() + " version=" + result.currentVersion() + " status="
                    + result.status());
        }
        return 0;
    }

    private static int triggers(DbPoolConfig db, LoadArm arm, PrintStream out) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(db), db.user(), db.password())) {
            TriggerControl.State state =
                    arm == null ? TriggerControl.read(connection) : TriggerControl.apply(connection, arm);
            out.println(state);
        }
        return 0;
    }

    private static JsonObject overlay(String conf) throws IOException {
        if (conf == null || conf.isBlank()) {
            return new JsonObject();
        }
        String trimmed = conf.trim();
        return new JsonObject(trimmed.startsWith("{") ? trimmed : Files.readString(Path.of(trimmed)));
    }

    private static void close(Vertx vertx) {
        if (vertx == null) {
            return;
        }
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The process is about to exit; a slow close must not change the command's result.
        }
    }
}
