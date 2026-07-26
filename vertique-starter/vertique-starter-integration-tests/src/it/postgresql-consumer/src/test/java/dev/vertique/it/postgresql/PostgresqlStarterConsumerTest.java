// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbModule;
import dev.vertique.db.MigrationRunner;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayMigrationStartupStep;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.starter.core.CoreApplicationModule;
import dev.vertique.starter.postgresql.PostgresqlPersistenceModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Consumer contract tests for {@code vertique-starter-postgresql}, executed from an isolated Maven
 * repository against the published starter artifact.
 *
 * <p>The PostgreSQL starter is an independent capability rather than an application foundation, so
 * the graph proof differs from the core/REST/services fixtures: the production consumer is a plain
 * compile marker, and the Dagger graph is assembled here, at test scope, by composing the aggregate
 * with the core application starter that supplies the Vert.x seam and config parser.
 */
class PostgresqlStarterConsumerTest {

    /** Timeout applied when closing the Vert.x instance a graph test owns. */
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    /** Fully-qualified names of the application-foundation modules this aggregate must never reach. */
    private static final List<String> FORBIDDEN_INCLUDE_NAMES = List.of(
            "dev.vertique.starter.core.CoreApplicationModule",
            "dev.vertique.starter.rest.RestApplicationModule",
            "dev.vertique.starter.services.ServicesApplicationModule",
            "dev.vertique.management.ManagementModule");

    // --- Aggregate shape ---

    @Test
    void aggregateMembershipIsFrozen() {
        Module module = PostgresqlPersistenceModule.class.getAnnotation(Module.class);
        assertNotNull(module, "PostgresqlPersistenceModule must be annotated with @dagger.Module");

        Class<?>[] includes = module.includes();
        assertEquals(
                3,
                includes.length,
                "PostgreSQL aggregate membership must be exactly three modules: " + Arrays.toString(includes));

        Set<Class<?>> actual = Arrays.stream(includes).collect(Collectors.toSet());
        Set<Class<?>> expected = Set.of(DbModule.class, DbPostgresqlModule.class, DbFlywayModule.class);
        assertEquals(expected, actual, "PostgreSQL aggregate membership drifted from the frozen contract");
    }

    @Test
    void aggregateDeclaresNoBindingsOrState() {
        int modifiers = PostgresqlPersistenceModule.class.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), "Aggregate must be public");
        assertTrue(Modifier.isAbstract(modifiers), "Aggregate must be abstract");

        assertEquals(
                0,
                PostgresqlPersistenceModule.class.getDeclaredFields().length,
                "Aggregate must declare no state: "
                        + Arrays.toString(PostgresqlPersistenceModule.class.getDeclaredFields()));
        assertEquals(
                0,
                PostgresqlPersistenceModule.class.getDeclaredMethods().length,
                "Aggregate must declare no bindings: "
                        + Arrays.toString(PostgresqlPersistenceModule.class.getDeclaredMethods()));
        assertEquals(
                0,
                PostgresqlPersistenceModule.class.getDeclaredClasses().length,
                "Aggregate must declare no nested types: "
                        + Arrays.toString(PostgresqlPersistenceModule.class.getDeclaredClasses()));

        Annotation[] annotations = PostgresqlPersistenceModule.class.getDeclaredAnnotations();
        assertEquals(1, annotations.length, "Aggregate must carry only @dagger.Module: " + Arrays.toString(annotations));
        assertEquals(
                Module.class,
                annotations[0].annotationType(),
                "Aggregate must carry no scope or qualifier annotation beyond @dagger.Module");
    }

    // --- Composed graph ---

    @Test
    void componentBuildsPostgresqlPoolAndMigrationBindings() throws Exception {
        withGraph(graph -> {
            assertNotNull(graph.pool(), "PostgreSQL pooling binding (io.vertx.sqlclient.Pool) must resolve");
            assertNotNull(graph.migrationRunner(), "Migration binding (MigrationRunner) must resolve");

            Set<ApplicationStartupStep> startupSteps = graph.startupSteps();
            assertNotNull(startupSteps, "startupSteps() must resolve");
            ApplicationStartupStep migrationStep = startupSteps.stream()
                    .filter(FlywayMigrationStartupStep.class::isInstance)
                    .findFirst()
                    .orElse(null);
            assertNotNull(
                    migrationStep,
                    "PostgreSQL starter must contribute the Flyway migration startup step, found " + startupSteps);
            assertEquals(
                    LifecyclePhase.MIGRATE,
                    migrationStep.phase(),
                    "The Flyway migration step must be contributed at the MIGRATE lifecycle phase");
        });
    }

    @Test
    void aggregateDoesNotSupplyApplicationLifecycleOrDeployments() throws Exception {
        Module module = PostgresqlPersistenceModule.class.getAnnotation(Module.class);
        assertNotNull(module, "PostgresqlPersistenceModule must be annotated with @dagger.Module");

        List<String> includeNames =
                Arrays.stream(module.includes()).map(Class::getName).toList();
        List<String> applicationModules = includeNames.stream()
                .filter(FORBIDDEN_INCLUDE_NAMES::contains)
                .toList();
        assertTrue(
                applicationModules.isEmpty(),
                "The persistence aggregate must include no application-foundation module, found " + applicationModules);

        withGraph(graph -> {
            Set<VerticleDeployment> deployments = graph.verticleDeployments();
            assertNotNull(deployments, "verticleDeployments() must resolve");
            assertTrue(
                    deployments.isEmpty(),
                    "The persistence aggregate must contribute no VerticleDeployment; the composed graph's"
                            + " deployment set must stay empty, found " + deployments);
        });
    }

    // --- Internal helpers ---

    /**
     * Builds the test-local graph from a real {@link Vertx} instance, runs the given assertions
     * against it, and always closes Vert.x afterwards.
     *
     * @param assertions the assertions to run against the built graph; must not be {@code null}
     * @throws Exception when closing the Vert.x instance fails or times out
     */
    private static void withGraph(Consumer<PersistenceGraph> assertions) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            PersistenceGraph graph = DaggerPostgresqlStarterConsumerTest_PersistenceGraph.builder()
                    .vertxModule(new VertxModule(vertx, minimalDatabaseConfig()))
                    .build();
            assertions.accept(graph);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Returns the smallest {@code db} configuration section {@code DbModule}'s config boundary needs
     * for {@code DbPostgresqlModule} to build connect options. The values are inert placeholders: a
     * Vert.x SQL client pool is constructed lazily, so no socket is opened and no server has to
     * exist. There is no Testcontainers dependency and no network access anywhere in this fixture.
     *
     * @return the application configuration passed to {@link VertxModule}
     */
    private static JsonObject minimalDatabaseConfig() {
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", "localhost")
                                .put("port", 5432)
                                .put("database", "vertique")
                                .put("user", "vertique")
                                .put("password", "vertique"));
    }

    /**
     * Test-local Dagger graph composing the persistence aggregate with the core application starter.
     *
     * <p>This is the shape an application uses: {@link PostgresqlPersistenceModule} never stands
     * alone, because it supplies no {@link Vertx} seam, config parser, or lifecycle multibindings.
     * {@link CoreApplicationModule} contributes those, and {@code VertxModule} — stateful, and
     * reached transitively through the core starter — stays a builder input.
     */
    @Singleton
    @Component(modules = {PostgresqlPersistenceModule.class, CoreApplicationModule.class})
    interface PersistenceGraph {

        /**
         * Returns the PostgreSQL connection pool the aggregate composes.
         *
         * @return the pool; never {@code null}
         */
        Pool pool();

        /**
         * Returns the Flyway-backed migration runner the aggregate composes.
         *
         * @return the migration runner; never {@code null}
         */
        MigrationRunner migrationRunner();

        /**
         * Returns the merged application startup steps, including the aggregate's
         * {@link LifecyclePhase#MIGRATE}-phase Flyway contribution.
         *
         * @return the startup step set; never {@code null}
         */
        Set<ApplicationStartupStep> startupSteps();

        /**
         * Returns the merged verticle deployment set, proving the aggregate deploys nothing.
         *
         * @return the deployment set; never {@code null}, and empty for this graph
         */
        Set<VerticleDeployment> verticleDeployments();
    }
}
