// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.serialization.KafkaSerdeRegistry;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApicurioAvroSerdeProvider} that do not require a running Schema Registry:
 * format key, {@code SpecificRecord} auto-detection, discriminator reads via {@code matchValue}, and
 * integration through {@link KafkaSerdeRegistry} (format resolution + {@code mayBlock} propagation).
 * The full producer→consumer round-trip is covered by the Testcontainers ITs.
 */
class ApicurioAvroSerdeProviderTest {

    // --- Fixtures ---

    /** Minimal hand-written {@code SpecificRecord} so {@code autoDetects} can be exercised without codegen. */
    static class SampleSpecificRecord implements SpecificRecord {
        @Override
        public Schema getSchema() {
            return null;
        }

        @Override
        public Object get(int field) {
            return null;
        }

        @Override
        public void put(int field, Object value) {
            // no-op
        }
    }

    record PlainPojo(String name) {}

    private final ApicurioAvroSerdeProvider provider = new ApicurioAvroSerdeProvider(
            KafkaConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient())));

    private static GenericRecord orderRecord(String type, String id) {
        Schema schema = SchemaBuilder.record("Order")
                .namespace("test")
                .fields()
                .requiredString("type")
                .requiredString("id")
                .optionalString("note")
                .endRecord();
        GenericData.Record record = new GenericData.Record(schema);
        record.put("type", type);
        record.put("id", id);
        return record;
    }

    private static JsonObject endpointConfig(String url) {
        return new JsonObject()
                .put("schemaRegistry", new JsonObject().put("url", url))
                .put("serdeProperties", new JsonObject());
    }

    // --- Identity ---

    @Nested
    @DisplayName("provider identity")
    class Identity {

        @Test
        @DisplayName("format is 'avro' and the provider may block (registry HTTP on cache miss)")
        void formatAndMayBlock() {
            assertEquals("avro", provider.format());
            assertTrue(provider.mayBlock());
        }
    }

    // --- Auto-detect ---

    @Nested
    @DisplayName("auto-detect")
    class AutoDetect {

        @Test
        @DisplayName("a SpecificRecord type auto-detects as avro; a plain POJO does not")
        void detectsSpecificRecord() {
            assertTrue(provider.autoDetects(SampleSpecificRecord.class));
            assertFalse(provider.autoDetects(PlainPojo.class));
            assertFalse(provider.autoDetects(String.class));
        }
    }

    // --- Discriminator reads ---

    @Nested
    @DisplayName("matchValue (router discriminator)")
    class MatchValue {

        @Test
        @DisplayName("reads a string discriminator field from an Avro record")
        void readsField() {
            GenericRecord record = orderRecord("created", "42");
            assertEquals("created", provider.matchValue(record, "type"));
            assertEquals("42", provider.matchValue(record, "id"));
        }

        @Test
        @DisplayName("returns null for an unset (nullable) field")
        void nullField() {
            GenericRecord record = orderRecord("created", "42");
            assertNull(provider.matchValue(record, "note"));
        }

        @Test
        @DisplayName("throws for a non-Avro record")
        void nonAvroRecord() {
            assertThrows(IllegalArgumentException.class, () -> provider.matchValue(new PlainPojo("x"), "type"));
            assertThrows(IllegalArgumentException.class, () -> provider.matchValue(null, "type"));
        }
    }

    // --- SpecificRecord enforcement ---

    @Nested
    @DisplayName("SpecificRecord enforcement")
    class SpecificRecordEnforcement {

        @Test
        @DisplayName("building a serializer/deserializer for a non-SpecificRecord type fails fast")
        void rejectsNonSpecificRecord() {
            JsonObject cfg = endpointConfig("http://localhost:8080/apis/registry/v3");
            assertThrows(IllegalArgumentException.class, () -> provider.serializer(PlainPojo.class, cfg));
            assertThrows(IllegalArgumentException.class, () -> provider.deserializer(PlainPojo.class, cfg));
            assertThrows(IllegalArgumentException.class, () -> provider.serializer(String.class, cfg));
        }

        @Test
        @DisplayName("a SpecificRecord type is accepted")
        void acceptsSpecificRecord() {
            JsonObject cfg = endpointConfig("http://localhost:8080/apis/registry/v3");
            assertNotNull(provider.serializer(SampleSpecificRecord.class, cfg));
            assertNotNull(provider.deserializer(SampleSpecificRecord.class, cfg));
        }
    }

    // --- Registry integration ---

    @Nested
    @DisplayName("integration via KafkaSerdeRegistry")
    class RegistryIntegration {

        private final KafkaSerdeRegistry registry = new KafkaSerdeRegistry(Set.of(provider));

        @Test
        @DisplayName("a SpecificRecord payload resolves to avro via auto-detect")
        void autoDetectThroughRegistry() {
            assertEquals("avro", registry.resolveFormat(SampleSpecificRecord.class, new JsonObject(), null));
            assertEquals("json", registry.resolveFormat(PlainPojo.class, new JsonObject(), null));
        }

        @Test
        @DisplayName("built serializer/deserializer report mayBlock=true through the registry wrapper")
        void mayBlockPropagates() {
            JsonObject cfg = endpointConfig("http://localhost:8080/apis/registry/v3");
            assertTrue(
                    registry.serializer("avro", SampleSpecificRecord.class, cfg).mayBlock());
            assertTrue(registry.deserializer("avro", SampleSpecificRecord.class, cfg)
                    .mayBlock());
            assertTrue(registry.mayBlock("avro"));
        }

        @Test
        @DisplayName("the router routing SPI delegates to the provider's matchValue")
        void routingDelegates() {
            assertEquals("shipped", registry.matchValue("avro", orderRecord("shipped", "7"), "type"));
        }
    }
}
