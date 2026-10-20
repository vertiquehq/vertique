// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.flyway.MigrationRunException.Stage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that the named runner resolves migration locations through the class loader of the thread that
 * calls {@code run}. The scripts live only in JARs under a temporary directory, so no loader but the
 * ones the test builds can see them: not the runner's own, and not the Vert.x worker's ambient one.
 *
 * <p>The two instances use classpath prefixes that share leading characters, {@code db/it-loader/alpha}
 * and {@code db/it-loader/alpha-v2}, so a prefix match that is not segment-exact would show up as one
 * instance reading the other's scripts.
 */
@DisplayName("Named migrations resolve locations through the calling thread's class loader")
class NamedMigrationClassLoaderIT {

    private static final String ALPHA_PREFIX = "db/it-loader/alpha";
    private static final String ALPHA_V2_PREFIX = "db/it-loader/alpha-v2";

    private static final Set<MigrationDefinition> DECLARATIONS = new LinkedHashSet<>(List.of(
            new MigrationDefinition("alpha", List.of("classpath:" + ALPHA_PREFIX), List.of()),
            new MigrationDefinition("alpha-v2", List.of("classpath:" + ALPHA_V2_PREFIX), List.of())));

    /** Each instance owns one schema; the history table of an instance lives in it. */
    private static final DbSchemas PLACEMENT = new DbSchemas(Map.of("alpha", "it_alpha", "alpha-v2", "it_alpha_v2"));

    private static PostgreSQLContainer postgres;
    private static Vertx vertx;
    private static int databaseCounter;

    @TempDir
    Path tmp;

    @BeforeAll
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    static void startContainerAndVertx() throws Exception {
        postgres = new PostgreSQLContainer("postgres:16-alpine");
        postgres.start();
        // The single worker thread is created now, so it keeps the test class path as its ambient loader
        vertx = Vertx.vertx(new VertxOptions().setDisableTCCL(true).setWorkerPoolSize(1));
        ClassLoader ambient =
                await(vertx.executeBlocking(() -> Thread.currentThread().getContextClassLoader()));
        assertNull(ambient.getResource(ALPHA_PREFIX), "the worker's ambient loader must not see the scripts");
    }

    @AfterAll
    static void stopContainerAndVertx() throws Exception {
        await(vertx.close());
        postgres.stop();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("each instance migrates its own scripts from a JAR only the caller's loader can see")
    void migratesEachInstanceFromTheCallersLoader() throws Exception {
        // Given: both instances packaged in a JAR that only the caller's context class loader sees
        String database = freshDatabase();
        FlywayMigrationRunner runner = runner(database);
        try (URLClassLoader both = loader("both.jar", ALPHA_PREFIX, ALPHA_V2_PREFIX)) {
            // When: the caller runs with that loader as its context class loader, and again
            MigrationReport first = runAs(both, runner);
            MigrationReport second = runAs(both, runner);

            // Then: both instances applied their one script, and the rerun applies nothing
            assertEquals(List.of(1, 1), applied(first));
            assertEquals(List.of(0, 0), applied(second));

            // And: each history holds only its own script and each table sits in its own schema
            assertOwnScriptsAndTables(database);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a loader that cannot see an instance's prefix fails that instance and nothing else")
    void aLoaderWithoutAPrefixFailsThatInstance() throws Exception {
        // Given: a migrated database, and loaders that each see only one of the two prefixes
        String database = freshDatabase();
        FlywayMigrationRunner runner = runner(database);
        try (URLClassLoader both = loader("both.jar", ALPHA_PREFIX, ALPHA_V2_PREFIX);
                URLClassLoader alphaOnly = loader("alpha.jar", ALPHA_PREFIX);
                URLClassLoader alphaV2Only = loader("alpha-v2.jar", ALPHA_V2_PREFIX)) {
            runAs(both, runner);

            // When: the run uses the loader that has no alpha-v2 prefix
            MigrationRunException withoutV2 = failureOf(alphaOnly, runner);

            // Then: alpha-v2 fails with its report entry, and alpha, which ran before it, stays migrated
            assertEquals(Stage.MIGRATION, withoutV2.stage());
            assertEquals(List.of(MigrationStatus.MIGRATED, MigrationStatus.FAILED), statuses(withoutV2.report()));

            // When: the run uses the loader that has alpha-v2, whose prefix extends alpha's, but not alpha
            MigrationRunException withoutAlpha = failureOf(alphaV2Only, runner);

            // Then: alpha fails on its missing location instead of reading alpha-v2's scripts
            assertEquals(Stage.MIGRATION, withoutAlpha.stage());
            assertEquals(List.of(MigrationStatus.FAILED, MigrationStatus.NOT_RUN), statuses(withoutAlpha.report()));

            // And: the failed runs changed nothing
            assertOwnScriptsAndTables(database);
        }
    }

    // --- When helpers ---

    /** Runs the named batch from this thread with the given context class loader, restoring the old one. */
    private static MigrationReport runAs(ClassLoader loader, FlywayMigrationRunner runner) throws Exception {
        return withContextLoader(loader, () -> await(runner.run(vertx)));
    }

    private static MigrationRunException failureOf(ClassLoader loader, FlywayMigrationRunner runner) throws Exception {
        ExecutionException failure =
                withContextLoader(loader, () -> assertThrows(ExecutionException.class, () -> await(runner.run(vertx))));
        return assertInstanceOf(MigrationRunException.class, failure.getCause());
    }

    private static <T> T withContextLoader(ClassLoader loader, Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static List<Integer> applied(MigrationReport report) {
        return report.instances().stream()
                .map(MigrationInstanceResult::migrationsApplied)
                .toList();
    }

    private static List<MigrationStatus> statuses(MigrationReport report) {
        return report.instances().stream().map(MigrationInstanceResult::status).toList();
    }

    // --- Fixtures: JAR, runner and PostgreSQL ---

    /** A loader over a new JAR holding one trivial script per prefix, visible to nothing else. */
    private URLClassLoader loader(String jarName, String... prefixes) throws IOException {
        Path jar = tmp.resolve(jarName);
        Set<String> directories = new LinkedHashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String prefix : prefixes) {
                // Directory entries are what lets a class loader list a prefix at all
                for (int slash = prefix.indexOf('/'); slash >= 0; slash = prefix.indexOf('/', slash + 1)) {
                    putEntry(out, directories, prefix.substring(0, slash + 1), null);
                }
                String table = prefix.substring(prefix.lastIndexOf('/') + 1).replace('-', '_') + "_item";
                putEntry(out, directories, prefix + "/", null);
                putEntry(
                        out,
                        directories,
                        prefix + "/V1__create_" + table + ".sql",
                        "CREATE TABLE " + table + " (id INT);");
            }
        }
        return new URLClassLoader(
                jarName, new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    }

    private static void putEntry(JarOutputStream out, Set<String> seen, String name, String content)
            throws IOException {
        if (content == null && !seen.add(name)) {
            return;
        }
        out.putNextEntry(new JarEntry(name));
        if (content != null) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        out.closeEntry();
    }

    private static FlywayMigrationRunner runner(String database) {
        DbPoolConfig pool = DbPoolConfig.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .database(database)
                .user(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        return new FlywayMigrationRunner(FlywayConfig.builder().named(true).build(), pool, DECLARATIONS, PLACEMENT);
    }

    private static String freshDatabase() throws SQLException {
        String name = "flyway_loader_it_" + (++databaseCounter);
        try (Connection admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static void assertOwnScriptsAndTables(String database) throws SQLException {
        assertEquals(
                List.of("V1__create_alpha_item.sql"),
                column(database, "SELECT script FROM it_alpha.flyway_schema_history_alpha WHERE type = 'SQL'"));
        assertEquals(
                List.of("V1__create_alpha_v2_item.sql"),
                column(database, "SELECT script FROM it_alpha_v2.flyway_schema_history_alpha_v2 WHERE type = 'SQL'"));
        assertEquals(
                List.of("it_alpha.alpha_item", "it_alpha_v2.alpha_v2_item"),
                column(
                        database,
                        "SELECT table_schema || '.' || table_name FROM information_schema.tables"
                                + " WHERE table_name LIKE '%\\_item' ORDER BY 1"));
    }

    private static List<String> column(String database, String sql) throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
