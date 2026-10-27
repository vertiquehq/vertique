// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.WorkflowExecutorHarness;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;

/**
 * One running copy of the example application on a shared database, started the way the saga tests start it: the
 * startup steps before the verticle phases run, plus the step that records the code definitions in the durable
 * registry. A restart closes the node and starts another on the same database with the code of the next deploy.
 */
final class ScenarioNode implements AutoCloseable {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** Where an instance runs: the scope it started in and the exact definition it is pinned to. */
    record Pin(String startScope, String definitionScope, String definitionId, long version) {}

    /** The definitions and type names a node starts with. */
    static final class Spec {
        private final Vertx vertx;
        private final PostgresContainer database;
        private final List<WorkflowDefinition<?, ?>> definitions = new ArrayList<>();
        private final List<WorkflowTypeCatalogEntry> entries = new ArrayList<>();
        private boolean generatedCatalog = true;

        private Spec(Vertx vertx, PostgresContainer database) {
            this.vertx = vertx;
            this.database = database;
        }

        /** Adds the code definitions the node contributes. */
        Spec code(WorkflowDefinition<?, ?>... more) {
            definitions.addAll(List.of(more));
            return this;
        }

        /** Adds type names the node lists beside the generated catalog. */
        Spec types(List<WorkflowTypeCatalogEntry> more) {
            entries.addAll(more);
            return this;
        }

        /** Leaves the generated catalog out, so the node lists only the names given to {@link #types}. */
        Spec withoutGeneratedCatalog() {
            generatedCatalog = false;
            return this;
        }

        /** Starts the node; the start must succeed. */
        ScenarioNode start() {
            ScenarioNode node = attempt();
            if (node.failure != null) {
                node.close();
                throw new AssertionError("The node was expected to start", node.failure);
            }
            return node;
        }

        /** Starts the node and reports a startup failure on the result. */
        ScenarioNode attempt() {
            ScenarioWiring wiring = new ScenarioWiring(definitions, entries);
            VertxModule vertxModule = new VertxModule(vertx, config(database));
            ScenarioGraph graph = generatedCatalog
                    ? DaggerScenarioComponent.builder()
                            .vertxModule(vertxModule)
                            .scenarioWiring(wiring)
                            .build()
                    : DaggerHandCatalogComponent.builder()
                            .vertxModule(vertxModule)
                            .scenarioWiring(wiring)
                            .build();
            Throwable failure = failureOf(runStartupSteps(graph));
            WorkflowExecutorHarness executor = null;
            if (failure == null) {
                // The graph runs no deployer: only the executor entry is deployed, never a whole phase
                try {
                    executor = await(WorkflowExecutorHarness.deploy(vertx, graph.verticleDeployments()));
                } catch (AssertionError e) {
                    failure = e;
                }
            }
            return new ScenarioNode(graph, failure, executor);
        }
    }

    private final ScenarioGraph graph;
    private final Throwable failure;
    private final WorkflowExecutorHarness executor;
    private boolean closed;

    private ScenarioNode(ScenarioGraph graph, Throwable failure, WorkflowExecutorHarness executor) {
        this.graph = graph;
        this.failure = failure;
        this.executor = executor;
    }

    /** Begins a node on the database. */
    static Spec on(Vertx vertx, PostgresContainer database) {
        return new Spec(vertx, database);
    }

    /** The node's components. */
    ScenarioGraph graph() {
        return graph;
    }

    /** The startup failure, or null when the node started. */
    Throwable failure() {
        return failure;
    }

    Pool pool() {
        return graph.pgInboxOutboxRepository().pool();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (executor != null) {
            await(executor.undeploy());
        }
        await(pool().close());
    }

    // --- Reading the database ---

    /** The scopes, definition id and version an instance is pinned to. */
    Pin pin(WorkflowInstanceId instance) {
        Row row = await(pool().preparedQuery("""
                        SELECT scope_key, definition_scope_key, definition_id, definition_version
                        FROM workflow_instances WHERE id = $1
                        """).execute(Tuple.of(instance.value())))
                .iterator()
                .next();
        return new Pin(
                row.getString("scope_key"),
                row.getString("definition_scope_key"),
                row.getString("definition_id"),
                row.getLong("definition_version"));
    }

    /** The status of an instance. */
    String status(WorkflowInstanceId instance) {
        return await(pool().preparedQuery("SELECT status FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(instance.value())))
                .iterator()
                .next()
                .getString("status");
    }

    /** The step an instance is at. */
    String step(WorkflowInstanceId instance) {
        return await(pool().preparedQuery("SELECT current_step_id FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(instance.value())))
                .iterator()
                .next()
                .getString("current_step_id");
    }

    /** What an instance waits for: the wait type, or null when it does not wait. */
    String waitType(WorkflowInstanceId instance) {
        return await(pool().preparedQuery("SELECT wait_type FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(instance.value())))
                .iterator()
                .next()
                .getString("wait_type");
    }

    /** The one open task of an instance. */
    UUID openTask(WorkflowInstanceId instance) {
        RowSet<Row> rows = await(pool().preparedQuery(
                        "SELECT task_id FROM workflow_tasks" + " WHERE workflow_id = $1 AND status = 'OPEN'")
                .execute(Tuple.of(instance.value())));
        return rows.iterator().next().getUUID("task_id");
    }

    /** The plan hash stored with a definition row of the default scope. */
    String storedHash(String scopeKey, String definitionId, long version) {
        return await(pool().preparedQuery("""
                        SELECT plan_hash FROM workflow_definitions
                        WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                        """).execute(Tuple.of(scopeKey, definitionId, version)))
                .iterator()
                .next()
                .getString("plan_hash");
    }

    /** The status of a definition row. */
    String definitionStatus(String scopeKey, String definitionId, long version) {
        return await(pool().preparedQuery("""
                        SELECT status FROM workflow_definitions
                        WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                        """).execute(Tuple.of(scopeKey, definitionId, version)))
                .iterator()
                .next()
                .getString("status");
    }

    /** The number of definition rows of a scope and id. */
    long definitionRows(String scopeKey, String definitionId) {
        return await(pool().preparedQuery("""
                        SELECT count(*) AS total FROM workflow_definitions
                        WHERE scope_key = $1 AND definition_id = $2
                        """).execute(Tuple.of(scopeKey, definitionId)))
                .iterator()
                .next()
                .getLong("total");
    }

    // --- Waiting ---

    /**
     * Blocks until one reconciliation pass has run, which brings in the activation records and the documents of the
     * scopes other than the default one; startup reads only the default scope.
     */
    void reconcile() {
        await(graph.definitionPropagationPoller().pollNow());
    }

    /** Blocks until the engine has run the work recorded for the instance. */
    void awaitIdle(WorkflowInstanceId instance) {
        await(graph.executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
    }

    /** Blocks for the result of a future; a failure surfaces as the assertion error that carries its cause. */
    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("The operation failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("The operation did not finish", e);
        }
    }

    /** Blocks until the future has finished and returns its failure, or null when it succeeded. */
    static Throwable failureOf(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("The operation did not finish", e);
        }
    }

    // --- Database and startup ---

    /** Creates the workflow, inbox/outbox and job tables on a started database. */
    static void migrate(PostgresContainer database) {
        // The modules number their migrations from V1 each, so each location keeps its own history table.
        migrate(database, "classpath:db/migration/inbox-outbox", "flyway_schema_history_io");
        migrate(database, "classpath:db/migration/workflow", "flyway_schema_history_wf");
        migrate(database, "classpath:db/migration/job", "flyway_schema_history_job");
    }

    private static void migrate(PostgresContainer database, String location, String historyTable) {
        Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private static JsonObject config(PostgresContainer database) {
        var dbConfig = database.toPoolConfig();
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", dbConfig.host())
                                .put("port", dbConfig.port())
                                .put("database", dbConfig.database())
                                .put("user", dbConfig.user())
                                .put("password", dbConfig.password())
                                .put("maxPoolSize", 10))
                .put(
                        "flyway",
                        new JsonObject()
                                .put("mode", "DISABLED")
                                .put("jdbcUrl", database.jdbcUrl())
                                .put("user", dbConfig.user())
                                .put("password", dbConfig.password()))
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("pollIntervalMs", 100)))
                .put("management", new JsonObject().put("port", 0).put("host", "127.0.0.1"));
    }

    /**
     * Runs the startup steps before the first verticle phase, and the step that records the code definitions in the
     * durable registry, which sits in a verticle phase.
     */
    private static Future<Void> runStartupSteps(ScenarioGraph graph) {
        List<ApplicationStartupStep> steps = graph.startupSteps().stream()
                .filter(step -> step.phase().ordinal() < LifecyclePhase.BOOTSTRAP.ordinal()
                        || step.getClass().getSimpleName().equals("WorkflowDefinitionRegistryBootstrapStep"))
                .sorted(LifecycleOrdered.comparator())
                .toList();
        Future<Void> chain = Future.succeededFuture();
        for (ApplicationStartupStep step : steps) {
            chain = chain.compose(ignored -> step.start());
        }
        return chain;
    }
}
