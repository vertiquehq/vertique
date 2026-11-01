// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.await;

import dev.vertique.core.VertxModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds the application graphs of a two-node proof over the one database and the shared broker: an executor node that
 * deploys a workflow definition and applies commands, and a recording-only node that hosts a source and records the
 * signals it turns the records into. Each node has a pool of its own that logs its statements, and a class-load probe
 * on its context class loader. {@link #close()} stops both nodes and closes their pools.
 */
final class SignalNodes implements AutoCloseable {

    private static final Duration IDLE = Duration.ofSeconds(20);

    private final Vertx vertx;
    private final JsonMapperProfile profile;
    private final PostgresContainer database;
    private final List<Node> nodes = new ArrayList<>();
    private Pool reader;

    private SignalNodes(Vertx vertx, JsonMapperProfile profile, PostgresContainer database) {
        this.vertx = vertx;
        this.profile = profile;
        this.database = database;
    }

    /**
     * Opens the nodes of a proof that run under one system JSON profile, as the nodes of one JVM must, over a database of
     * their own on a container of their own, so that no definition row reaches another class's nodes and none of theirs
     * reaches these.
     *
     * @param vertx the Vert.x instance the nodes and their pools run on
     * @param profile the application JSON profile that both nodes select as {@code json.systemProfile}
     * @return the nodes, with none started
     */
    static SignalNodes open(Vertx vertx, JsonMapperProfile profile) {
        PostgresContainer database = new PostgresContainer()
                .withDatabaseName("workflow_kafka_recording_only")
                .withMigration("classpath:db/migration/workflow");
        database.start();
        return new SignalNodes(vertx, profile, database);
    }

    /**
     * Starts an executor node that deploys the fixture's definition under the id, with its executor running.
     *
     * @param fixture the isolated code the node alone is handed
     * @param definitionId the id the definition is deployed under
     * @return the running node
     */
    Node executor(SignalNodesFixture fixture, String definitionId) {
        JsonObject config = base().put(
                        "workflow",
                        new JsonObject()
                                .put("executor", new JsonObject().put("pollIntervalMs", 100))
                                .put("recording", recording()));
        return start(
                config, List.of(fixture.definition(definitionId)), List.of(), null, SignalNodes.class.getClassLoader());
    }

    /**
     * Starts a recording-only node, {@code workflow.executor.enabled=false}, that hosts the source over the topic.
     *
     * @param topic the topic and group of the source
     * @param source the definition of the source
     * @return the running node
     */
    Node recordingOnly(SignalTopic topic, Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>> source) {
        return recordingOnly(topic, source, SignalNodes.class.getClassLoader());
    }

    /** As {@link #recordingOnly(SignalTopic, Function)}, built under the class loader. */
    Node recordingOnly(
            SignalTopic topic, Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>> source, ClassLoader builtUnder) {
        JsonObject consumers = new JsonObject()
                .put(
                        topic.name(),
                        new JsonObject()
                                .put("deadLetterTopic", topic.deadLetterTopic())
                                .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                                .put(
                                        "retry",
                                        new JsonObject()
                                                .put("maxRetries", 2)
                                                .put("backoffMs", 50)
                                                .put("backoffMultiplier", 2.0)
                                                .put("maxBackoffMs", 60_000)
                                                .put("exhaustedStrategy", "DEAD_LETTER")));
        JsonObject config = base().put(
                        "kafka",
                        new JsonObject()
                                .put("bootstrap.servers", SourceScenario.Infrastructure.bootstrap())
                                .put("consumers", consumers))
                .put(
                        "workflow",
                        new JsonObject()
                                .put("executor", new JsonObject().put("enabled", false))
                                .put("recording", recording()));
        return start(config, List.of(), List.of(source), topic, builtUnder);
    }

    private JsonObject base() {
        return new JsonObject()
                .put("json", new JsonObject().put("systemProfile", profile.id().value()))
                .put("kafka", new JsonObject().put("bootstrap.servers", SourceScenario.Infrastructure.bootstrap()));
    }

    private static JsonObject recording() {
        return new JsonObject().put("maxOpenSignalsPerInstance", 100).put("maxPayloadBytes", 262_144);
    }

    private Node start(
            JsonObject config,
            List<WorkflowDefinition<?, ?>> definitions,
            List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources,
            SignalTopic topic,
            ClassLoader builtUnder) {
        StatementLog statements = new StatementLog();
        ClassLoadProbe probe = new ClassLoadProbe(builtUnder);
        RecordingInterceptor completions = new RecordingInterceptor();
        Pool pool = openPool();
        Node node = new Node(
                probe.during(() -> DaggerSignalNode.builder()
                        .vertxModule(new VertxModule(vertx, config))
                        .rig(new SignalNode.Rig(statements.observe(pool), profile, completions, definitions, sources))
                        .build()),
                statements,
                probe,
                completions,
                pool);
        nodes.add(node);
        probe.during(() -> {
            node.runStartupSteps();
            node.deployVerticles();
            await(node.graph().consumerDeployments().deployAll());
            return null;
        });
        if (topic != null) {
            topic.awaitAssigned();
        }
        return node;
    }

    /** A pool of its own that is not observed, for the proof to read the rows the nodes wrote. */
    synchronized Pool reader() {
        if (reader == null) {
            reader = openPool();
        }
        return reader;
    }

    private Pool openPool() {
        DbPoolConfig config = database.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(8))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    @Override
    public void close() {
        List<Throwable> failures = new ArrayList<>();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).close(failures);
        }
        if (reader != null) {
            try {
                await(reader.close());
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        try {
            database.close();
        } catch (Throwable failure) {
            failures.add(failure);
        }
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError("the nodes did not close cleanly", failures.get(0));
            failures.stream().skip(1).forEach(error::addSuppressed);
            throw error;
        }
    }

    /** One running node, with the observations made of it. */
    static final class Node {

        private final SignalNode graph;
        private final StatementLog statements;
        private final ClassLoadProbe classLoads;
        private final RecordingInterceptor completions;
        private final Pool pool;

        private Node(
                SignalNode graph,
                StatementLog statements,
                ClassLoadProbe classLoads,
                RecordingInterceptor completions,
                Pool pool) {
            this.graph = graph;
            this.statements = statements;
            this.classLoads = classLoads;
            this.completions = completions;
            this.pool = pool;
        }

        SignalNode graph() {
            return graph;
        }

        /** The statements the node executed. */
        StatementLog statements() {
            return statements;
        }

        /** The probe on the node's context class loader, and so the classes the node asked for by name. */
        ClassLoadProbe classLoads() {
            return classLoads;
        }

        /** The completions the node's consumers reported for each record. */
        RecordingInterceptor completions() {
            return completions;
        }

        /** The loader the node was built under, which is the parent of its probe. */
        ClassLoader builtUnder() {
            return classLoads.getParent();
        }

        /** The names of the verticles the node's graph offers to deploy. */
        Set<String> deploymentNames() {
            return graph.verticleDeployments().stream()
                    .map(VerticleDeployment::name)
                    .collect(java.util.stream.Collectors.toSet());
        }

        /** Whether the node's deployer holds a deployed verticle of the name. */
        boolean deployed(String name) {
            return graph.verticleDeployer().deploymentId(name) != null;
        }

        /** Waits for the call made on the node's behalf, with the node's probe installed on the calling thread. */
        <T> T call(Supplier<Future<T>> call) {
            return await(classLoads.during(call));
        }

        /** Starts an instance of the definition, in the scope, and waits until it waits for its signal. */
        WorkflowInstanceId startAndSettle(
                String definitionId, String contract, Object payload, String idempotencyKey, String scope) {
            WorkflowInstanceId started = call(() -> graph.workflowOperations()
                    .start(new StartCommand(
                            definitionId, contract, payload, idempotencyKey, null, null, null, scope, null)));
            awaitIdle(started);
            return started;
        }

        /** Waits until the node's executor has run all the work recorded for the instance. */
        void awaitIdle(WorkflowInstanceId instance) {
            await(graph.executionProbe().awaitIdle(instance, IDLE));
        }

        private void runStartupSteps() {
            List<ApplicationStartupStep> steps = graph.startupSteps().stream()
                    .filter(step -> step.phase().ordinal() < LifecyclePhase.BOOTSTRAP.ordinal()
                            || step.getClass().getName().endsWith(".WorkflowDefinitionRegistryBootstrapStep"))
                    .sorted(LifecycleOrdered.comparator())
                    .toList();
            for (ApplicationStartupStep step : steps) {
                await(step.start());
            }
        }

        private void deployVerticles() {
            for (VerticleDeployment deployment : graph.verticleDeployments()) {
                await(graph.verticleDeployer().deploy(deployment));
            }
        }

        private void close(List<Throwable> failures) {
            attempt(failures, () -> await(graph.consumerDeployments().undeployAll()));
            attempt(failures, () -> await(graph.verticleDeployer().undeployAll()));
            attempt(failures, () -> await(pool.close()));
        }

        private static void attempt(List<Throwable> failures, Runnable action) {
            try {
                action.run();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }
}
