// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import dev.vertique.kafka.serialization.TestJsonSerdeProvider;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Producer settings are validated when the factory is constructed, without connecting; the shared
 * producer is still created on the first send.
 *
 * <p>The Vert.x instance is a mock, so creating the real producer client during construction would
 * fail the test rather than reach a broker.
 */
class KafkaProducerFactoryStartupTest {

    private static final String MESSAGE = "No bootstrap.servers configured for Kafka producer";

    private static KafkaProducerFactory factoryFor(JsonObject kafka) {
        KafkaConfig config = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", kafka), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return new KafkaProducerFactory(
                mock(Vertx.class),
                config,
                KafkaTestSupport.noOpPropagator(),
                new KafkaSerdeRegistry(Set.of(new TestJsonSerdeProvider())));
    }

    @Test
    @DisplayName("a missing bootstrap.servers fails construction with the same message the first send used")
    void missingBootstrapFailsConstruction() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> factoryFor(new JsonObject()));

        assertEquals(MESSAGE, failure.getMessage());
    }

    @Test
    @DisplayName("a blank bootstrap.servers fails construction")
    void blankBootstrapFailsConstruction() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> factoryFor(new JsonObject().put("bootstrap.servers", "  ")));

        assertEquals(MESSAGE, failure.getMessage());
    }

    @Test
    @DisplayName("a blank value in a higher layer overrides a configured lower layer and fails construction")
    void blankInHigherLayerWins() {
        JsonObject kafka = new JsonObject()
                .put("bootstrap.servers", "localhost:9092")
                .put("producer", new JsonObject().put("properties", new JsonObject().put("bootstrap.servers", "")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> factoryFor(kafka));

        assertEquals(MESSAGE, failure.getMessage());
    }

    @Test
    @DisplayName(
            "bootstrap.servers from any merged layer is accepted: root flat, root nested, properties, producer bag")
    void everyMergedLayerIsAccepted() {
        assertDoesNotThrow(() -> factoryFor(new JsonObject().put("bootstrap.servers", "localhost:9092")));
        assertDoesNotThrow(
                () -> factoryFor(new JsonObject().put("bootstrap", new JsonObject().put("servers", "localhost:9092"))));
        assertDoesNotThrow(() -> factoryFor(
                new JsonObject().put("properties", new JsonObject().put("bootstrap.servers", "localhost:9092"))));
        assertDoesNotThrow(() -> factoryFor(new JsonObject()
                .put(
                        "producer",
                        new JsonObject()
                                .put("properties", new JsonObject().put("bootstrap.servers", "localhost:9092")))));
    }

    @Test
    @DisplayName("an unreachable address constructs fine and no producer is created until the first send")
    @SuppressWarnings("unchecked")
    void constructionDoesNotConnect() throws Exception {
        KafkaProducerFactory factory = factoryFor(new JsonObject().put("bootstrap.servers", "127.0.0.1:1"));

        Field shared = KafkaProducerFactory.class.getDeclaredField("shared");
        shared.setAccessible(true);
        assertNull(((AtomicReference<Object>) shared.get(factory)).get(), "the shared producer stays lazy");
    }
}
