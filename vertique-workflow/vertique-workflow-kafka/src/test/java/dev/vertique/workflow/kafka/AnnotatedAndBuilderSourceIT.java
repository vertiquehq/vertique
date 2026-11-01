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
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.workflow.kafka.CarrierSignalTypes.OrderShipping;
import dev.vertique.workflow.kafka.CarrierSignalTypes.StartShipping;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, on a real broker and a real PostgreSQL engine, that an annotated {@code @KafkaListener} interface and a
 * source written with the builder, given the same contract, signal, key accessor, dedup accessor and default
 * {@code onNoMatch}, handle the same records the same way: the matching record resumes its instance with the same
 * payload, and the record for a key nobody waits on commits with no retry and no dead-letter entry. The annotated
 * listener is compiled by the real code generators of this module's test processor path.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AnnotatedAndBuilderSourceIT {

    private static final Duration SETTLE = Duration.ofSeconds(30);

    @Test
    void annotatedAndBuilderSourcesBehaveTheSame(Vertx vertx) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String firstKey = "K1-" + unique;
        String secondKey = "K2-" + unique;
        // Given the Kafka and PostgreSQL containers, an annotated listener on its own topic and a builder source on
        // another, a recording observer, and a node of the generated registrations module that runs both
        RecordingObserver observer = new RecordingObserver();
        RecordingInterceptor completions = new RecordingInterceptor();
        PostgresContainer database = new PostgresContainer()
                .withDatabaseName("workflow_kafka_annotated")
                .withMigration("classpath:db/migration/workflow");
        database.start();
        Pool pool = openPool(vertx, database);
        AnnotatedAndBuilderNode node = null;
        try (SignalTopic annotated =
                        SignalTopic.createExact(CarrierSignals.TOPIC, CarrierSignals.GROUP, CarrierSignals.NAME);
                SignalTopic builder = SignalTopic.create("carrier-builder")) {
            node = DaggerAnnotatedAndBuilderNode.builder()
                    .vertxModule(new VertxModule(vertx, config(annotated, builder)))
                    .rig(new AnnotatedAndBuilderNode.Rig(pool, observer, completions, builder))
                    .build();
            startUp(node);
            annotated.awaitAssigned();
            builder.awaitAssigned();
            Scenario scenario = new Scenario(node, pool, observer, completions, unique);
            // And two instances that wait, one on key K1 and one on key K2
            WorkflowInstanceId first = scenario.waiting(firstKey);
            WorkflowInstanceId second = scenario.waiting(secondKey);

            // When the same ShipmentDelivered shape is produced to each topic, once with the waiting key and once with
            // a
            // key no instance waits on
            Observed onAnnotated = scenario.run(annotated, first, firstKey);
            Observed onBuilder = scenario.run(builder, second, secondKey);

            // Then on both topics the matching record resumed its instance with the same payload, and the observer saw
            // RECORDED and then NO_MATCH
            assertThat(onAnnotated.signalOutcomes()).containsExactly(Outcome.RECORDED, Outcome.NO_MATCH);
            assertThat(onAnnotated.resumedWithMatchingEvent()).isTrue();
            // And the unmatched record committed with no retry and no dead-letter entry
            assertThat(onAnnotated.unmatchedCompletions()).hasSize(1);
            assertThat(SourceScenario.COMMITTED_WITHOUT_RETRY).containsAll(onAnnotated.unmatchedCompletions());
            assertThat(onAnnotated.deadLetters()).isZero();
            // And the builder source's records ended the same way
            assertThat(onBuilder).isEqualTo(onAnnotated);
        } finally {
            shutDown(node, pool, database);
        }
    }

    /**
     * What a proof reads of one topic after its two records.
     *
     * @param signalOutcomes the outcomes the delivery bean reported for the source, oldest first
     * @param instanceStatus the status of the instance that waited on the topic's key
     * @param resumedWithMatchingEvent whether the state of the instance holds the id of the matching event the proof
     *     produced to the topic; each topic gets event ids of its own, because dedup is per contract, signal and key
     * @param unmatchedCompletions the terminal outcomes of the record for the key nobody waited on
     * @param deadLetters the number of records on the topic's dead-letter topic
     */
    record Observed(
            List<Outcome> signalOutcomes,
            String instanceStatus,
            boolean resumedWithMatchingEvent,
            List<KafkaTerminalOutcome> unmatchedCompletions,
            int deadLetters) {}

    /** Starts instances and feeds one topic, and reads what the topic's source did. */
    private static final class Scenario {

        private final AnnotatedAndBuilderNode node;
        private final Pool pool;
        private final RecordingObserver observer;
        private final RecordingInterceptor completions;
        private final String unique;
        private int starts;

        Scenario(
                AnnotatedAndBuilderNode node,
                Pool pool,
                RecordingObserver observer,
                RecordingInterceptor completions,
                String unique) {
            this.node = node;
            this.pool = pool;
            this.observer = observer;
            this.completions = completions;
            this.unique = unique;
        }

        /** Starts an instance that waits for a delivery under the carrier reference, and waits until it waits. */
        WorkflowInstanceId waiting(String carrierRef) {
            WorkflowInstanceId started = await(node.workflowOperations()
                    .start(new StartCommand(
                            OrderShipping.ID,
                            "carrier-orders",
                            new StartShipping(carrierRef),
                            "start-" + unique + "-" + ++starts,
                            null,
                            null,
                            null,
                            "acme",
                            null)));
            await(node.executionProbe().awaitIdle(started, SETTLE));
            return started;
        }

        /** Produces a matching record and then one for a key nobody waits on, and reads what happened to them. */
        Observed run(SignalTopic topic, WorkflowInstanceId instance, String waitingKey) {
            String nobody = "NOBODY-" + unique;
            String matchingEvent = "evt-matching-" + topic.name();
            Produced matching = topic.produce(waitingKey, event(matchingEvent, waitingKey));
            settle(matching);
            await(node.executionProbe().awaitIdle(instance, SETTLE));
            Produced unmatched = topic.produce(nobody, event("evt-unmatched-" + topic.name(), nobody));
            settle(unmatched);
            Row row = await(pool.preparedQuery(
                                    "SELECT status, state_json::text AS state FROM workflow_instances WHERE id = $1")
                            .execute(Tuple.of(instance.value())))
                    .iterator()
                    .next();
            return new Observed(
                    observer.outcomesOf(topic.name()),
                    row.getString("status"),
                    matchingEvent.equals(
                            SourceScenario.jsonObject(row.getString("state")).getString("eventId")),
                    completions.outcomesOf(unmatched),
                    DlqReader.of(SourceScenario.Infrastructure.bootstrap(), topic.deadLetterTopic())
                            .read()
                            .size());
        }

        private void settle(Produced record) {
            completions
                    .awaitSettled(record, SETTLE)
                    .orElseThrow(() -> new AssertionError(
                            "the record " + record + " did not settle: " + completions.outcomesOf(record)));
        }

        private static String event(String eventId, String carrierRef) {
            return new JsonObject()
                    .put("eventId", eventId)
                    .put("carrierRef", carrierRef)
                    .encode();
        }
    }

    private static JsonObject config(SignalTopic annotated, SignalTopic builder) {
        JsonObject consumers = new JsonObject()
                .put(annotated.name(), consumer(annotated))
                .put(builder.name(), consumer(builder))
                // the source class of the same generated module has no topic in this proof
                .put(ShipmentDeliveredSource.NAME, new JsonObject().put("enabled", false));
        return new JsonObject()
                .put(
                        "kafka",
                        new JsonObject()
                                .put("bootstrap.servers", SourceScenario.Infrastructure.bootstrap())
                                .put("consumers", consumers))
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

    private static JsonObject consumer(SignalTopic topic) {
        return new JsonObject()
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
    }

    /** Runs the startup steps up to the definition registry, deploys the executor and then the consumers. */
    private static void startUp(AnnotatedAndBuilderNode node) {
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

    /** Undeploys the consumers and the executor, then closes the pool and the database. */
    private static void shutDown(AnnotatedAndBuilderNode node, Pool pool, PostgresContainer database) {
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
