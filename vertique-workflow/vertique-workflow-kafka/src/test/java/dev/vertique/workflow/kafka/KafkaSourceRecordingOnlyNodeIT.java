// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.ShipmentSignals.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.kafka.ShipmentSignals.DeliveryConfirmed;
import dev.vertique.workflow.kafka.ShipmentSignals.OrdersContract;
import dev.vertique.workflow.kafka.ShipmentSignals.ShipmentDelivered;
import dev.vertique.workflow.kafka.SignalNodes.Node;
import dev.vertique.workflow.kafka.StatementLog.Execution;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, on a real broker and one PostgreSQL database, that a Kafka signal source runs on a node that has no executor
 * and none of the workflow's classes, and that the signal it records is applied by the executor of another node with
 * its date unchanged.
 *
 * <p>Two application graphs run in the one test JVM. The executor node deploys the workflow definition, which is
 * compiled into a class loader that only that node is handed. The recording-only node, configured with
 * {@code workflow.executor.enabled=false}, hosts the source and installs the JSON runtime through
 * {@code KafkaJsonModule}. Both select the same system JSON profile, which writes an instant as microseconds since the
 * epoch instead of as text, so that a date written or read through any other codec would not round-trip. The process
 * has one JSON codec, so the nodes cannot be given different profiles and the proof does not try.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaSourceRecordingOnlyNodeIT {

    /** A date with sub-second precision, so that a truncation shows. */
    private static final Instant DELIVERED_AT = Instant.parse("2026-10-30T12:34:56.789123Z");

    private static final Duration SETTLE = Duration.ofSeconds(30);
    private static final JsonMapperProfile PROFILE = new EpochMicrosProfile("recording-only-source-epoch-micros");

    /** Frees the process JSON codec of the profile of an earlier class, where the JVM allows it. */
    @BeforeAll
    static void startFromAnUntouchedProcessCodec() {
        resetProcessCodec();
    }

    /** Hands the process JSON codec back, where the JVM allows it, for the classes that run after this one. */
    @AfterAll
    static void leaveTheProcessCodecUntouched() {
        resetProcessCodec();
    }

    @Test
    void signalRecordedOnARecordingOnlyNodeIsAppliedOnTheExecutorNodeWithItsDateIntact(Vertx vertx) throws IOException {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String definitionId = "shipment-watch-" + unique;
        String carrierRef = "PAY-77-" + unique;
        // Given a system profile whose encoding of the date differs from the default's, so that a date that went
        // through
        // any other codec would not round-trip
        assertThat(PROFILE.mapper().writeValueAsString(DELIVERED_AT))
                .isNotEqualTo(new DefaultJsonMapperProfileRegistry(Set.of())
                        .mapper(JsonProfileId.SYSTEM)
                        .writeValueAsString(DELIVERED_AT));
        SignalNodesFixture fixture = SignalNodesFixture.compile();
        try (fixture;
                SignalTopic carriers = SignalTopic.create("carrier.events");
                SignalNodes nodes = SignalNodes.open(vertx, PROFILE)) {
            // And an executor node E that deploys the isolated ShipmentWatch, and one instance waiting on PAY-77
            Node executor = nodes.executor(fixture, definitionId);
            WorkflowInstanceId watch = executor.startAndSettle(
                    definitionId, "orders", fixture.start("watch-" + unique, carrierRef), "start-" + unique, "acme");
            // And a recording-only node R, workflow.executor.enabled=false, that hosts the source over the topic
            Node recorder =
                    nodes.recordingOnly(carriers, signals -> signals.on(carriers.topic(), ShipmentDelivered.class)
                            .groupId(carriers.group())
                            .name(carriers.name())
                            .dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(event -> new DeliveryConfirmed(event.deliveredAt()))
                            .build());

            // When the test produces one ShipmentDelivered record for PAY-77 and E has run what R recorded
            SourceScenario.Produced record = carriers.produce(
                    carrierRef,
                    new JsonObject()
                            .put("eventId", "evt-" + unique)
                            .put("carrierRef", carrierRef)
                            .put("deliveredAt", DELIVERED_AT.toString())
                            .encode());
            recorder.completions()
                    .awaitSettled(record, SETTLE)
                    .orElseThrow(() -> new AssertionError("the record did not settle on the recording-only node"));
            executor.awaitIdle(watch);

            // Then R recorded the SIGNAL command and E, not R, claimed it and finished it
            Rows rows = new Rows(nodes.reader(), watch);
            Row command = rows.signalCommand();
            assertThat(recorder.statements().commandInserts("SIGNAL")).hasSize(1);
            assertThat(executor.statements().commandInserts("SIGNAL")).isEmpty();
            assertThat(command.getString("state")).isEqualTo("COMPLETE");
            assertThat(claimedCommandIds(executor)).contains(command.getLong("id"));
            assertThat(recorder.statements().claims()).isEmpty();

            // And E applied the signal: the history holds it and the stored state holds the date that was sent
            assertThat(rows.historyTypes()).contains("SIGNAL_RECEIVED");
            assertThat(fixture.deliveredAt(VertiqueJson.mapper(), rows.state())).isEqualTo(DELIVERED_AT);

            // And R wrote the date in the profile's own encoding, microseconds since the epoch, which the default's
            // text form and any millisecond or second form would not match
            Object recorded = new JsonObject(command.getString("payload")).getValue("deliveredAt");
            assertThat(recorded).isInstanceOf(Number.class);
            assertThat(((Number) recorded).longValue())
                    .isEqualTo(ChronoUnit.MICROS.between(Instant.EPOCH, DELIVERED_AT));

            // And R has no executor, loaded none of the fixture's classes, and wrote no definition
            assertThat(recorder.classLoads().contextsCreatedDuringCarryTheProbe(vertx))
                    .as("the probe sits on the contexts of R, so that an empty answer means something")
                    .isTrue();
            assertThat(recorder.classLoads().requestedWithPrefix(SignalNodesFixture.PACKAGE))
                    .isEmpty();
            assertThat(SignalNodesFixture.visibleTo(recorder.builtUnder())).isFalse();
            assertThat(recorder.deploymentNames()).doesNotContain(WorkflowExecutor.DEPLOYMENT_NAME);
            assertThat(recorder.deployed(WorkflowExecutor.DEPLOYMENT_NAME)).isFalse();
            assertThat(recorder.statements().definitionWrites()).isEmpty();
            // Whereas E is an executor that holds the code and wrote its definition, so that the same questions have
            // an answer other than none
            assertThat(executor.deployed(WorkflowExecutor.DEPLOYMENT_NAME)).isTrue();
            assertThat(executor.statements().definitionWrites()).isNotEmpty();
        }
        assertThat(fixture.removed())
                .as("the directory of the isolated classes is deleted")
                .isTrue();
    }

    /** The ids of the commands that E took for its executor. */
    private static List<Long> claimedCommandIds(Node executor) {
        List<Long> ids = new ArrayList<>();
        for (Execution claim : executor.statements().claims()) {
            ids.add(((Number) claim.values().get(0)).longValue());
        }
        return ids.stream().distinct().toList();
    }

    private static void resetProcessCodec() {
        if (Boolean.getBoolean("vertique.json.codec.allowReset")) {
            VertiqueJson.resetForTests();
        }
    }

    /** The rows one instance has, read through a pool that no node uses. */
    private static final class Rows {

        private final Pool pool;
        private final WorkflowInstanceId instance;

        Rows(Pool pool, WorkflowInstanceId instance) {
            this.pool = pool;
            this.instance = instance;
        }

        /** The one {@code SIGNAL} command of the instance. */
        Row signalCommand() {
            List<Row> rows = new ArrayList<>();
            SourceScenario.await(pool.preparedQuery("SELECT id, state, claimed_by, payload_json::text AS payload"
                                    + " FROM workflow_commands WHERE workflow_id = $1 AND kind = 'SIGNAL'")
                            .execute(Tuple.of(instance.value())))
                    .forEach(rows::add);
            assertThat(rows).as("the SIGNAL commands of the instance").hasSize(1);
            return rows.get(0);
        }

        /** The entry types of the history, oldest first. */
        List<String> historyTypes() {
            List<String> types = new ArrayList<>();
            for (Row row : SourceScenario.await(pool.preparedQuery(
                            "SELECT entry_type FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                    .execute(Tuple.of(instance.value())))) {
                types.add(row.getString("entry_type"));
            }
            return types;
        }

        /** The stored state of the instance as JSON text. */
        String state() {
            Row row = SourceScenario.await(
                            pool.preparedQuery("SELECT state_json::text AS state FROM workflow_instances WHERE id = $1")
                                    .execute(Tuple.of(instance.value())))
                    .iterator()
                    .next();
            return SourceScenario.jsonObject(row.getString("state")).encode();
        }
    }

    /**
     * A system profile that is the built-in one with a single difference: an instant is written as the number of
     * microseconds since the epoch, and read the same way. The built-in profile writes it as text.
     */
    private static final class EpochMicrosProfile implements JsonMapperProfile {

        private final JsonProfileId id;
        private final ObjectMapper mapper;

        EpochMicrosProfile(String id) {
            this.id = JsonProfileId.of(id);
            ObjectMapper system = new DefaultJsonMapperProfileRegistry(Set.of()).mapper(JsonProfileId.SYSTEM);
            SimpleModule micros = new SimpleModule("epoch-micros-instants");
            micros.addSerializer(Instant.class, new StdSerializer<>(Instant.class) {
                @Override
                public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
                        throws IOException {
                    generator.writeNumber(ChronoUnit.MICROS.between(Instant.EPOCH, value));
                }
            });
            micros.addDeserializer(Instant.class, new StdDeserializer<>(Instant.class) {
                @Override
                public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                    return Instant.EPOCH.plus(parser.getLongValue(), ChronoUnit.MICROS);
                }
            });
            this.mapper = system.copy().registerModule(micros);
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }
}
