// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the serialization half of the secret rule on the config mapper: a field whose dotted path is
 * sensitive renders as {@code "***"} when set and disappears when absent, nested and dot-spanning
 * paths are recognised, open bags are scrubbed by entry, non-secret fields are untouched, and the
 * behaviour holds on the lenient default, on a finalized {@code @ConfigMapper} override and through
 * {@code convertValue}/{@code valueToTree}.
 */
@DisplayName("ConfigSecretMaskingModule")
class ConfigSecretMaskingModuleTest {

    private static final String SECRET = "s3cr3t-value";

    private final ObjectMapper mapper = DefaultConfigMapper.lenient();

    record Credentials(String user, String password, String apiKey) {}

    /** {@code auth} is a neutral holder; {@code credentials} is itself a secret-named key. */
    record Server(
            String host, int port, Credentials auth, Credentials credentials, Ssl ssl, Optional<String> accessToken) {}

    record Ssl(String keyStorePath, String keyStorePassword, boolean enabled) {}

    /** {@code jaas.config} is sensitive only as a dotted path: neither segment matches alone. */
    record Jaas(String config, String mechanism) {}

    record Kafka(Jaas jaas, String clientId) {}

    record Bag(String name, JsonObject properties, Map<String, String> headers, List<JsonObject> items) {}

    record Plain(String host, String region, int retries) {}

    record Tokens(int maxTokens, List<String> tokenScopes) {}

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record ArrayShaped(String name, String password) {}

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record ArrayShapedBag(String name, Map<String, String> headers) {}

    record Wrapped(String name, Optional<Map<String, String>> headers, Object extras, OptionalInt tokenCount) {}

    private JsonNode tree(Object value) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(value));
    }

    @Nested
    @DisplayName("sensitive fields")
    class SensitiveFields {

        @Test
        @DisplayName("render as *** when set and the siblings written after them stay intact")
        void presentSecretMasked() throws Exception {
            JsonNode json = tree(new Credentials("app", SECRET, "key-123"));

            assertEquals("app", json.get("user").asText());
            assertEquals("***", json.get("password").asText());
            assertEquals("***", json.get("apiKey").asText());
            assertFalse(json.toString().contains(SECRET));
        }

        @Test
        @DisplayName("are omitted when null or an empty Optional")
        void absentSecretOmitted() throws Exception {
            JsonNode json = tree(new Server("h", 1, new Credentials("app", null, null), null, null, Optional.empty()));

            assertFalse(json.has("accessToken"), json.toString());
            assertFalse(json.get("auth").has("password"), json.toString());
            assertFalse(json.get("auth").has("apiKey"), json.toString());
            assertEquals("app", json.get("auth").get("user").asText());
        }

        @Test
        @DisplayName("a present Optional secret renders as ***")
        void presentOptionalMasked() throws Exception {
            JsonNode json = tree(new Server("h", 1, null, null, null, Optional.of(SECRET)));

            assertEquals("***", json.get("accessToken").asText());
        }

        @Test
        @DisplayName("a secret-named record masks as one *** and is not descended into")
        void sensitiveParentMaskedWhole() throws Exception {
            JsonNode json =
                    tree(new Server("h", 1, null, new Credentials("app", SECRET, null), null, Optional.empty()));

            assertEquals("***", json.get("credentials").asText());
            assertFalse(json.toString().contains("app"));
        }

        @Test
        @DisplayName("the dotted path is built from the enclosing keys: ssl.keyStorePassword")
        void nestedPathMasked() throws Exception {
            JsonNode json =
                    tree(new Server("h", 1, null, null, new Ssl("/etc/ks.p12", SECRET, true), Optional.empty()));

            JsonNode ssl = json.get("ssl");
            assertEquals("***", ssl.get("keyStorePassword").asText());
            assertTrue(ssl.get("enabled").asBoolean());
            // The shared rule's keystore token also covers the path: accepted consequence of one rule.
            assertEquals("***", ssl.get("keyStorePath").asText());
            assertEquals("h", json.get("host").asText());
        }

        @Test
        @DisplayName("a token that spans the dot (jaas.config) is recognised through the path")
        void dotSpanningTokenMasked() throws Exception {
            JsonNode json = tree(new Kafka(new Jaas(SECRET, "PLAIN"), "orders"));

            assertEquals("***", json.get("jaas").get("config").asText());
            assertEquals("PLAIN", json.get("jaas").get("mechanism").asText());
            assertEquals("orders", json.get("clientId").asText());
        }

        @Test
        @DisplayName("records inside a list keep the list's path and mask too")
        void recordsInListMasked() throws Exception {
            JsonNode json = tree(Map.of("servers", List.of(new Credentials("a", SECRET, null))));

            assertEquals("***", json.get("servers").get(0).get("password").asText());
            assertEquals("a", json.get("servers").get(0).get("user").asText());
        }

        @Test
        @DisplayName("a number or list whose name matches the rule masks as *** as well")
        void nonStringSensitiveFieldsMasked() throws Exception {
            JsonNode json = tree(new Tokens(4096, List.of("read")));

            assertEquals("***", json.get("maxTokens").asText());
            assertEquals("***", json.get("tokenScopes").asText());
        }

        @Test
        @DisplayName("an array-shaped bean masks positionally")
        void arrayShapedMasked() throws Exception {
            JsonNode json = tree(new ArrayShaped("n", SECRET));

            assertTrue(json.isArray(), json.toString());
            assertEquals("n", json.get(0).asText());
            assertEquals("***", json.get(1).asText());
            assertTrue(tree(new ArrayShaped("n", null)).get(1).isNull());
        }
    }

    @Nested
    @DisplayName("open bags")
    class OpenBags {

        @Test
        @DisplayName("sensitive entries mask at every depth and null secrets are dropped")
        void bagEntriesMasked() throws Exception {
            JsonObject properties = new JsonObject()
                    .put("sasl.password", SECRET)
                    .put("bootstrap", "k:9092")
                    .put("nested", new JsonObject().put("api-key", SECRET).put("depth", 2))
                    .put("ssl.keystore.password", (String) null);
            Map<String, String> headers = Map.of("Authorization", "Bearer x", "X-Token", SECRET);
            JsonNode json = tree(new Bag(
                    "n",
                    properties,
                    headers,
                    List.of(new JsonObject().put("secretKey", SECRET).put("id", 7))));

            assertFalse(json.toString().contains(SECRET), json.toString());
            JsonNode props = json.get("properties");
            assertEquals("***", props.get("sasl.password").asText());
            assertEquals("k:9092", props.get("bootstrap").asText());
            assertEquals("***", props.get("nested").get("api-key").asText());
            assertEquals(2, props.get("nested").get("depth").asInt());
            assertFalse(props.has("ssl.keystore.password"));
            assertEquals("Bearer x", json.get("headers").get("Authorization").asText());
            assertEquals("***", json.get("headers").get("X-Token").asText());
            assertEquals("***", json.get("items").get(0).get("secretKey").asText());
            assertEquals(7, json.get("items").get(0).get("id").asInt());
        }

        @Test
        @DisplayName("bags held through Optional or Object and in array-shaped beans are scrubbed too")
        void wrappedBagsMasked() throws Exception {
            Map<String, String> headers = Map.of("X-Token", SECRET, "Accept", "json");
            JsonNode wrapped = tree(new Wrapped(
                    "n", Optional.of(headers), new JsonObject().put("sasl.password", SECRET), OptionalInt.empty()));
            JsonNode arrayShaped = tree(new ArrayShapedBag("n", headers));

            assertFalse(wrapped.toString().contains(SECRET), wrapped.toString());
            assertEquals("***", wrapped.get("headers").get("X-Token").asText());
            assertEquals("json", wrapped.get("headers").get("Accept").asText());
            assertEquals("***", wrapped.get("extras").get("sasl.password").asText());
            assertFalse(wrapped.has("tokenCount"), "an empty OptionalInt secret is omitted: " + wrapped);
            assertFalse(arrayShaped.toString().contains(SECRET), arrayShaped.toString());
            assertEquals("***", arrayShaped.get(1).get("X-Token").asText());
        }

        @Test
        @DisplayName("a bag without secrets serializes through the regular writer unchanged")
        void cleanBagUntouched() throws Exception {
            JsonObject properties = new JsonObject().put("bootstrap", "k:9092").put("acks", "all");
            JsonNode json = tree(new Bag("n", properties, Map.of("a", "b"), List.of()));

            assertEquals(mapper.readTree(properties.encode()), json.get("properties"));
            assertEquals("b", json.get("headers").get("a").asText());
            assertEquals(0, json.get("items").size());
        }
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("a record without secret-named fields serializes exactly as before")
        void plainRecordUntouched() throws Exception {
            assertEquals(
                    "{\"host\":\"h\",\"region\":\"eu\",\"retries\":3}",
                    mapper.writeValueAsString(new Plain("h", "eu", 3)));
        }

        @Test
        @DisplayName("a finalized @ConfigMapper override masks the same way")
        void overrideMapperMasks() throws Exception {
            ObjectMapper override =
                    DefaultConfigMapper.finalizeForConfig(JsonMapper.builder().build());

            JsonNode json = override.readTree(override.writeValueAsString(new Credentials("app", SECRET, null)));

            assertEquals("***", json.get("password").asText());
            assertFalse(json.has("apiKey"));
        }

        @Test
        @DisplayName("valueToTree and convertValue see the same masking as writeValueAsString")
        void treeConversionsMask() {
            Credentials credentials = new Credentials("app", SECRET, "k");

            assertEquals("***", mapper.valueToTree(credentials).get("password").asText());
            JsonNode nested = mapper.valueToTree(new Server("h", 1, credentials, null, null, Optional.empty()));
            assertEquals("***", nested.get("auth").get("apiKey").asText(), nested.toString());
            assertEquals("app", nested.get("auth").get("user").asText());
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = mapper.convertValue(credentials, Map.class);
            assertEquals("***", asMap.get("apiKey"));
            assertEquals("app", asMap.get("user"));
        }

        @Test
        @DisplayName("a plain ObjectMapper without the module still prints the secret")
        void plainMapperNotAffected() throws Exception {
            assertTrue(new ObjectMapper()
                    .writeValueAsString(new Credentials("app", SECRET, null))
                    .contains(SECRET));
        }

        @Test
        @DisplayName("deserialization is untouched: the live value stays readable")
        void deserializationUntouched() throws Exception {
            Credentials parsed =
                    mapper.readValue("{\"user\":\"app\",\"password\":\"" + SECRET + "\"}", Credentials.class);

            assertEquals(SECRET, parsed.password());
            JsonArray none = new JsonArray();
            assertEquals(none, mapper.readValue("[]", JsonArray.class));
        }
    }
}
