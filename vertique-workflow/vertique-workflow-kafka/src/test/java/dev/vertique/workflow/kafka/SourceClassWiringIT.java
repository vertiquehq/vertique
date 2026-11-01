// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.kafka.SourceScenario.OrderShipping;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
import dev.vertique.workflow.kafka.SourceScenario.StartShipping;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, on a real broker and a real PostgreSQL engine, that a source class which the generated wiring registered runs
 * a consumer, and that the lookup written in its {@code define} uses the repository the class received by constructor
 * injection. The node names the generated registrations module and has no {@code @Provides} method for the class.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SourceClassWiringIT {

    private static final Duration SETTLE = Duration.ofSeconds(30);
    private static final String BUSINESS_KEY = "PO-42";
    private static final String DELIVERED_AT = "2026-03-01T10:00:00Z";

    @Test
    void sourceClassWithInjectedRepositoryRunsConsumerWithoutProvidesMethod(Vertx vertx) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String trackingRef = "TRACK-" + unique;
        // Given the Kafka and PostgreSQL containers, a lookup fake that answers PO-42 for the tracking reference, and a
        // node of the generated registrations module with no @Provides method for the source class
        ShipmentLookup.Recording lookup = new ShipmentLookup.Recording().knows(trackingRef, BUSINESS_KEY);
        RecordingObserver observer = new RecordingObserver();
        RecordingInterceptor completions = new RecordingInterceptor();
        PostgresContainer database = new PostgresContainer()
                .withDatabaseName("workflow_kafka_source_class")
                .withMigration("classpath:db/migration/workflow");
        database.start();
        Pool pool = openPool(vertx, database);
        SourceClassNode node = null;
        try (SignalTopic topic = SignalTopic.createExact(
                ShipmentDeliveredSource.TOPIC, ShipmentDeliveredSource.GROUP, ShipmentDeliveredSource.NAME)) {
            node = DaggerSourceClassNode.builder()
                    .vertxModule(new VertxModule(vertx, config(topic)))
                    .rig(new SourceClassNode.Rig(pool, observer, completions, lookup))
                    .build();
            startUp(node);
            topic.awaitAssigned();
            // And one instance started with business key PO-42 that waits for its delivery
            WorkflowInstanceId started = await(node.workflowOperations()
                    .start(new StartCommand(
                            OrderShipping.ID,
                            "orders",
                            new StartShipping(trackingRef),
                            "start-" + unique,
                            BUSINESS_KEY,
                            null,
                            null,
                            "acme",
                            null)));
            await(node.executionProbe().awaitIdle(started, SETTLE));

            // When one ShipmentDelivered record whose lookup answers PO-42 is produced
            Produced record = topic.produce(
                    trackingRef,
                    new JsonObject()
                            .put("eventId", "evt-" + unique)
                            .put("type", "delivered")
                            .put("carrierRef", trackingRef)
                            .put("deliveredAt", DELIVERED_AT)
                            .encode());
            completions
                    .awaitSettled(record, SETTLE)
                    .orElseThrow(
                            () -> new AssertionError("the record did not settle: " + completions.outcomesOf(record)));
            await(node.executionProbe().awaitIdle(started, SETTLE));

            // Then the lookup was called once with the record's tracking reference
            assertThat(lookup.asked()).containsExactly(trackingRef);
            // And the signal is recorded
            assertThat(observer.outcomesOf(ShipmentDeliveredSource.NAME)).containsExactly(Outcome.APPLIED);
            // And the instance resumed with the mapped payload
            Row instance = await(
                            pool.preparedQuery("SELECT state_json::text AS state FROM workflow_instances WHERE id = $1")
                                    .execute(Tuple.of(started.value())))
                    .iterator()
                    .next();
            assertThat(SourceScenario.jsonObject(instance.getString("state")).getString("deliveredAt"))
                    .isEqualTo(DELIVERED_AT);
            assertThat(historyTypes(pool, started)).contains("SIGNAL_RECEIVED");
        } finally {
            shutDown(node, pool, database);
        }
    }

    private static JsonObject config(SignalTopic topic) {
        JsonObject consumer = new JsonObject()
                .put("deadLetterTopic", topic.deadLetterTopic())
                .put("properties", new JsonObject().put("auto.offset.reset", "earliest"))
                .put(
                        "retry",
                        new JsonObject()
                                .put("maxRetries", 2)
                                .put("backoffMs", 50)
                                .put("backoffMultiplier", 2.0)
                                .put("maxBackoffMs", 60_000)
                                .put("exhaustedStrategy", "DEAD_LETTER"));
        return new JsonObject()
                .put(
                        "kafka",
                        new JsonObject()
                                .put("bootstrap.servers", SourceScenario.Infrastructure.bootstrap())
                                .put("consumers", new JsonObject().put(topic.name(), consumer)))
                .put(
                        "workflow",
                        new JsonObject()
                                .put("executor", new JsonObject().put("pollIntervalMs", 100))
                                .put(
                                        "recording",
                                        new JsonObject()
                                                .put("maxOpenSignalsPerInstance", 100)
                                                .put("maxPayloadBytes", 262_144)));
    }

    /** Runs the startup steps up to the definition registry, deploys the executor and then the consumers. */
    private static void startUp(SourceClassNode node) {
        List<ApplicationStartupStep> steps = node.startupSteps().stream()
                .filter(step -> step.phase().ordinal() < LifecyclePhase.BOOTSTRAP.ordinal()
                        || step.getClass().getName().endsWith(".WorkflowDefinitionRegistryBootstrapStep"))
                .sorted(LifecycleOrdered.comparator())
                .toList();
        for (ApplicationStartupStep step : steps) {
            await(step.start());
        }
        for (VerticleDeployment deployment : node.verticleDeployments()) {
            await(node.verticleDeployer().deploy(deployment));
        }
        await(node.consumerDeployments().deployAll());
    }

    private static List<String> historyTypes(Pool pool, WorkflowInstanceId instance) {
        List<String> types = new ArrayList<>();
        for (Row row : await(pool.preparedQuery("SELECT entry_type FROM workflow_history WHERE workflow_id = $1")
                .execute(Tuple.of(instance.value())))) {
            types.add(row.getString("entry_type"));
        }
        return types;
    }

    /** Undeploys the consumers and the executor, then closes the pool and the database. */
    private static void shutDown(SourceClassNode node, Pool pool, PostgresContainer database) {
        try {
            if (node != null) {
                await(node.consumerDeployments().undeployAll());
                await(node.verticleDeployer().undeployAll());
            }
        } finally {
            try {
                await(pool.close());
            } finally {
                database.close();
            }
        }
    }

    private static Pool openPool(Vertx vertx, PostgresContainer database) {
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
}
