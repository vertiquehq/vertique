// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaConfigHelper}: verifies that both {@code resolveString} and
 * {@code flattenToMap} correctly handle flat dotted keys, nested object hierarchies,
 * mixed representations, and edge cases such as missing keys, partial paths, and
 * non-string leaf values.
 */
class KafkaConfigHelperTest {

    // --- resolveString ---

    @Nested
    @DisplayName("resolveString()")
    class ResolveString {

        @Test
        @DisplayName("flat dotted key present returns its value")
        void flatKeyPresentReturnsValue() {
            JsonObject obj = new JsonObject().put("bootstrap.servers", "broker:9092");
            assertEquals("broker:9092", KafkaConfigHelper.resolveString(obj, "bootstrap.servers"));
        }

        @Test
        @DisplayName("nested single-level path present returns its value")
        void nestedSingleLevelReturnsValue() {
            JsonObject obj = new JsonObject().put("bootstrap", new JsonObject().put("servers", "broker:9092"));
            assertEquals("broker:9092", KafkaConfigHelper.resolveString(obj, "bootstrap.servers"));
        }

        @Test
        @DisplayName("multi-level nesting resolves sasl.jaas.config")
        void multiLevelNestingReturnsValue() {
            JsonObject obj = new JsonObject()
                    .put(
                            "sasl",
                            new JsonObject().put("jaas", new JsonObject().put("config", "required username=\"u\";")));
            assertEquals("required username=\"u\";", KafkaConfigHelper.resolveString(obj, "sasl.jaas.config"));
        }

        @Test
        @DisplayName("key not present in flat or nested form returns null")
        void keyAbsentReturnsNull() {
            JsonObject obj = new JsonObject().put("other.key", "value");
            assertNull(KafkaConfigHelper.resolveString(obj, "bootstrap.servers"));
        }

        @Test
        @DisplayName("flat key takes precedence over nested form when both present")
        void flatKeyWinsOverNested() {
            JsonObject obj = new JsonObject()
                    .put("bootstrap.servers", "flat-broker:9092")
                    .put("bootstrap", new JsonObject().put("servers", "nested-broker:9092"));
            assertEquals("flat-broker:9092", KafkaConfigHelper.resolveString(obj, "bootstrap.servers"));
        }

        @Test
        @DisplayName("non-string leaf value is converted via toString()")
        void nonStringLeafConvertedToString() {
            JsonObject obj = new JsonObject().put("max.poll.records", 100);
            assertEquals("100", KafkaConfigHelper.resolveString(obj, "max.poll.records"));
        }

        @Test
        @DisplayName("partial nested path that does not go deep enough returns null")
        void partialPathReturnsNull() {
            // "sasl" exists but has no "jaas" child — path is too short
            JsonObject obj = new JsonObject().put("sasl", new JsonObject().put("mechanism", "PLAIN"));
            assertNull(KafkaConfigHelper.resolveString(obj, "sasl.jaas.config"));
        }

        @Test
        @DisplayName("empty JsonObject returns null")
        void emptyObjectReturnsNull() {
            assertNull(KafkaConfigHelper.resolveString(new JsonObject(), "bootstrap.servers"));
        }
    }

    // --- flattenToMap ---

    @Nested
    @DisplayName("flattenToMap()")
    class FlattenToMap {

        @Test
        @DisplayName("empty JsonObject produces empty map")
        void emptyObjectProducesEmptyMap() {
            Map<String, String> result = KafkaConfigHelper.flattenToMap(new JsonObject());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("already-flat object returns all entries as strings")
        void alreadyFlatReturnsAllEntries() {
            JsonObject obj =
                    new JsonObject().put("bootstrap.servers", "broker:9092").put("security.protocol", "SASL_SSL");

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals(2, result.size());
            assertEquals("broker:9092", result.get("bootstrap.servers"));
            assertEquals("SASL_SSL", result.get("security.protocol"));
        }

        @Test
        @DisplayName("single-level nesting is flattened with dot separator")
        void singleLevelNestingFlattened() {
            JsonObject obj = new JsonObject().put("bootstrap", new JsonObject().put("servers", "broker:9092"));

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals(1, result.size());
            assertEquals("broker:9092", result.get("bootstrap.servers"));
        }

        @Test
        @DisplayName("multi-level nesting is fully flattened with joined dots")
        void multiLevelNestingFullyFlattened() {
            JsonObject obj = new JsonObject()
                    .put(
                            "sasl",
                            new JsonObject().put("jaas", new JsonObject().put("config", "required username=\"u\";")));

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals(1, result.size());
            assertEquals("required username=\"u\";", result.get("sasl.jaas.config"));
        }

        @Test
        @DisplayName("mixed flat and nested keys both appear in result")
        void mixedFlatAndNestedBothPresent() {
            JsonObject obj = new JsonObject()
                    .put("bootstrap.servers", "broker:9092")
                    .put("sasl", new JsonObject().put("mechanism", "PLAIN"));

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals(2, result.size());
            assertEquals("broker:9092", result.get("bootstrap.servers"));
            assertEquals("PLAIN", result.get("sasl.mechanism"));
        }

        @Test
        @DisplayName("non-string leaf values (integers, booleans) are converted to string")
        void nonStringLeafValuesConvertedToString() {
            JsonObject obj = new JsonObject().put("max.poll.records", 500).put("enable.auto.commit", false);

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals("500", result.get("max.poll.records"));
            assertEquals("false", result.get("enable.auto.commit"));
        }

        @Test
        @DisplayName("null leaf values are skipped and not included in the result")
        void nullLeafValuesSkipped() {
            JsonObject obj =
                    new JsonObject().put("bootstrap.servers", "broker:9092").putNull("security.protocol");

            Map<String, String> result = KafkaConfigHelper.flattenToMap(obj);

            assertEquals(1, result.size());
            assertTrue(result.containsKey("bootstrap.servers"));
            assertFalse(result.containsKey("security.protocol"));
        }
    }

    // --- serdeConfig ---

    @Nested
    @DisplayName("serdeConfig()")
    class SerdeConfig {

        @Test
        @DisplayName("two-arg overload equals three-arg overload called with a null profile (delegation)")
        void twoArgEqualsThreeArgWithNullProfile() {
            JsonObject registry = new JsonObject().put("url", "http://registry:8081");
            JsonObject serdeProperties = new JsonObject().put("specific.avro.reader", "true");

            // Given/when: the restored two-arg overload must produce the same byte-for-byte bag as the
            // three-arg overload with no profile selected (the vertx/default path). Then: equal bags,
            // proving the two-arg form delegates with a null profile and the default path is unchanged.
            assertEquals(
                    KafkaConfigHelper.serdeConfig(registry, serdeProperties, null),
                    KafkaConfigHelper.serdeConfig(registry, serdeProperties));
        }

        @Test
        @DisplayName("two-arg overload omits the jsonProfile key (default path is byte-for-byte unchanged)")
        void twoArgOmitsJsonProfileKey() {
            JsonObject registry = new JsonObject().put("url", "http://registry:8081");
            JsonObject serdeProperties = new JsonObject().put("specific.avro.reader", "true");

            JsonObject bag = KafkaConfigHelper.serdeConfig(registry, serdeProperties);

            assertFalse(
                    bag.containsKey("jsonProfile"),
                    "two-arg serdeConfig must omit jsonProfile so the default path is unchanged");
            assertEquals(serdeProperties, bag.getJsonObject("serdeProperties"));
            assertEquals(registry, bag.getJsonObject("schemaRegistry"));
        }

        @Test
        @DisplayName("serdeConfig_withProfile_putsJsonProfileKey: bag carries jsonProfile, not the retired key")
        void serdeConfig_withProfile_putsJsonProfileKey() {
            JsonObject registry = new JsonObject().put("url", "http://registry:8081");
            JsonObject serdeProperties = new JsonObject().put("specific.avro.reader", "true");

            JsonObject bag = KafkaConfigHelper.serdeConfig(registry, serdeProperties, "payments-v2");

            assertEquals(
                    "payments-v2",
                    bag.getString("jsonProfile"),
                    "the resolved profile must be written under the harmonized jsonProfile key");
            assertFalse(
                    bag.containsKey("valueJsonProfile"), "the retired valueJsonProfile key must no longer be written");
        }

        @Test
        @DisplayName("serdeConfig_withBlankProfile_doesNotPutJsonProfileKey: neither key present for blank/null")
        void serdeConfig_withBlankProfile_doesNotPutJsonProfileKey() {
            JsonObject registry = new JsonObject().put("url", "http://registry:8081");
            JsonObject serdeProperties = new JsonObject().put("specific.avro.reader", "true");

            JsonObject blankBag = KafkaConfigHelper.serdeConfig(registry, serdeProperties, "");
            JsonObject nullBag = KafkaConfigHelper.serdeConfig(registry, serdeProperties, null);

            assertFalse(blankBag.containsKey("jsonProfile"), "blank profile must not add the jsonProfile key");
            assertFalse(blankBag.containsKey("valueJsonProfile"), "blank profile must not add the retired key");
            assertFalse(nullBag.containsKey("jsonProfile"), "null profile must not add the jsonProfile key");
            assertFalse(nullBag.containsKey("valueJsonProfile"), "null profile must not add the retired key");
        }
    }
}
