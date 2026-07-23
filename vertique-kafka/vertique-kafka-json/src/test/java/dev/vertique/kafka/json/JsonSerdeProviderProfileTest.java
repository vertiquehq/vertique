// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonSerdeProvider} profile resolution (slice 4.2): the provider reads the
 * {@code jsonProfile} key from the merged serde-config bag and resolves the backing
 * {@code ObjectMapper} from an injected {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>The {@code "no-coercion"} application profile disables
 * {@link MapperFeature#ALLOW_COERCION_OF_SCALARS}, so {@code {"count":"5"}} (a string where an
 * {@code int} is expected) is rejected by that profile but accepted by the lenient {@code vertx}
 * default. That observable difference proves which mapper each <em>deserializing</em> method used.
 *
 * <p>For the <em>serializing</em> path (slice 4.3, producer threading), the {@code "indent"}
 * application profile enables {@link SerializationFeature#INDENT_OUTPUT}, so a record serializes to
 * pretty-printed bytes (containing a newline) under the profile mapper but to compact bytes under the
 * {@code vertx} default — proving {@link JsonSerdeProvider#serializer} honors the resolved profile
 * mapper. {@code INDENT_OUTPUT} changes only whitespace, so the registry's structural round-trip probe
 * still passes (unlike {@code JsonInclude.Include.NON_NULL}, which would drop fields and fail it).
 *
 * <p>The security-message tests (W2 fix) assert that deserialization-failure messages are value-free:
 * the offending scalar ({@code "5"}) must not appear in the {@link dev.vertique.kafka.DeserializationException}
 * message string, but the Jackson cause must still be preserved for diagnostics.
 */
@DisplayName("JsonSerdeProvider profile resolution")
class JsonSerdeProviderProfileTest {

    // --- Fixtures ---

    /** Target with an {@code int} component, used to exercise scalar coercion strictness. */
    record Counter(int count) {}

    /** Simple target serialized to compact-vs-pretty bytes to observe the serializer's mapper. */
    record Dto(String value) {}

    private static final JsonProfileId NO_COERCION = JsonProfileId.of("no-coercion");

    private static final JsonProfileId INDENT = JsonProfileId.of("indent");

    private static JsonSerdeProvider providerWithStrictProfile() {
        JsonMapperProfile strictProfile = JsonMapperProfiles.of(
                NO_COERCION,
                JsonMapper.builder()
                        .addModule(VertxJsonSupport.module())
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build());
        return new JsonSerdeProvider(
                new DefaultJsonMapperProfileRegistry(Set.of(strictProfile)), JsonConfig.defaults());
    }

    private static JsonSerdeProvider providerWithIndentProfile() {
        JsonMapperProfile indentProfile = JsonMapperProfiles.of(
                INDENT,
                JsonMapper.builder()
                        .addModule(VertxJsonSupport.module())
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .build());
        return new JsonSerdeProvider(
                new DefaultJsonMapperProfileRegistry(Set.of(indentProfile)), JsonConfig.defaults());
    }

    private static JsonObject serdeConfigWith(String profileId) {
        return dev.vertique.kafka.KafkaConfigHelper.serdeConfig(new JsonObject(), new JsonObject(), profileId);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // --- deserializer() ---

    @Nested
    @DisplayName("deserializer() uses the resolved profile mapper")
    class DeserializerProfile {

        @Test
        @DisplayName("deserializer_usesProfileMapper: no-coercion profile rejects string->int; vertx coerces")
        void deserializer_usesProfileMapper() throws Exception {
            JsonSerdeProvider provider = providerWithStrictProfile();

            KafkaDeserializer<Counter> strict = provider.deserializer(Counter.class, serdeConfigWith("no-coercion"));
            assertThrows(
                    DeserializationException.class,
                    () -> strict.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()));

            KafkaDeserializer<Counter> lenient = provider.deserializer(Counter.class, serdeConfigWith(null));
            assertEquals(new Counter(5), lenient.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()));
        }
    }

    // --- serializer() ---

    @Nested
    @DisplayName("serializer() uses the resolved profile mapper")
    class SerializerProfile {

        @Test
        @DisplayName("producerSerializer_usesProfileMapper: indent profile pretty-prints; vertx stays compact")
        void producerSerializer_usesProfileMapper() {
            JsonSerdeProvider provider = providerWithIndentProfile();

            KafkaSerializer<Dto> indent = provider.serializer(Dto.class, serdeConfigWith("indent"));
            String indentJson = text(indent.serialize(new Dto("x"), "t", Map.of()));
            assertTrue(indentJson.contains("\n"), indentJson);

            KafkaSerializer<Dto> vertx = provider.serializer(Dto.class, serdeConfigWith(null));
            String vertxJson = text(vertx.serialize(new Dto("x"), "t", Map.of()));
            assertFalse(vertxJson.contains("\n"), vertxJson);
            assertEquals("{\"value\":\"x\"}", vertxJson);
        }
    }

    // --- routingDeserializer() + convertRouted() ---

    @Nested
    @DisplayName("routingDeserializer() and convertRouted() use the resolved profile mapper")
    class RoutingProfile {

        @Test
        @DisplayName("routingDeserializer_and_convertRouted_useProfileMapper: no-coercion rejects string->int")
        void routingDeserializer_and_convertRouted_useProfileMapper() throws Exception {
            JsonSerdeProvider provider = providerWithStrictProfile();
            JsonObject strictBag = serdeConfigWith("no-coercion");

            Object strictTree =
                    provider.routingDeserializer(strictBag).deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of());
            assertNotNull(strictTree);
            assertThrows(
                    DeserializationException.class, () -> provider.convertRouted(strictTree, Counter.class, strictBag));

            JsonObject vertxBag = serdeConfigWith(null);
            Object vertxTree =
                    provider.routingDeserializer(vertxBag).deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of());
            assertEquals(new Counter(5), provider.convertRouted(vertxTree, Counter.class, vertxBag));
        }
    }

    // --- serde-bag key rename (S6): jsonProfile selects, stale valueJsonProfile is ignored ---

    @Nested
    @DisplayName("resolveMapper reads the renamed jsonProfile bag key, not the retired valueJsonProfile key")
    class BagKeyRename {

        @Test
        @DisplayName("resolveMapper_withJsonProfileKeyInBag_usesNamedProfile:"
                + " the new jsonProfile key selects the named (strict) profile")
        void resolveMapper_withJsonProfileKeyInBag_usesNamedProfile() {
            JsonSerdeProvider provider = providerWithStrictProfile();

            // Given a bag carrying the NEW key "jsonProfile"="no-coercion" (the registered strict profile).
            JsonObject bag = new JsonObject().put("jsonProfile", "no-coercion");

            // When/then: the strict mapper is selected — it rejects string->int coercion.
            KafkaDeserializer<Counter> strict = provider.deserializer(Counter.class, bag);
            assertThrows(
                    DeserializationException.class,
                    () -> strict.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()),
                    "the jsonProfile bag key must select the named strict profile");
        }

        @Test
        @DisplayName("resolveMapper_withValueJsonProfileKeyInBag_doesNotSelectProfile:"
                + " the retired valueJsonProfile bag key is ignored → vertx default coerces")
        void resolveMapper_withValueJsonProfileKeyInBag_doesNotSelectProfile() throws Exception {
            JsonSerdeProvider provider = providerWithStrictProfile();

            // Given a bag carrying ONLY the retired key "valueJsonProfile"="no-coercion" (stale).
            JsonObject staleBag = new JsonObject().put("valueJsonProfile", "no-coercion");

            // When/then: post-rename the reader no longer reads that key, so no profile is selected and
            // resolution falls to the permissive vertx default — string->int coercion succeeds.
            KafkaDeserializer<Counter> deser = provider.deserializer(Counter.class, staleBag);
            assertEquals(
                    new Counter(5),
                    deser.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()),
                    "the retired valueJsonProfile bag key must be ignored → vertx default (coercion) used");
        }
    }

    // --- vertx default unchanged ---

    @Nested
    @DisplayName("vertx default behaves as DatabindCodec.mapper()")
    class VertxDefault {

        @Test
        @DisplayName("vertxDefault_unchanged: absent and explicit 'vertx' profile both coerce string->int")
        void vertxDefault_unchanged() throws Exception {
            JsonSerdeProvider provider = providerWithStrictProfile();

            KafkaDeserializer<Counter> absent = provider.deserializer(Counter.class, serdeConfigWith(null));
            assertEquals(new Counter(5), absent.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()));

            KafkaDeserializer<Counter> vertx = provider.deserializer(Counter.class, serdeConfigWith("vertx"));
            assertEquals(new Counter(5), vertx.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()));
        }
    }

    // --- unknown profile id fails fast ---

    @Nested
    @DisplayName("unknown profile id fails fast at deserializer-build time")
    class UnknownProfile {

        @Test
        @DisplayName("unknownProfileId_throws: an unregistered id raises JsonProfileConfigurationException")
        void unknownProfileId_throws() {
            JsonSerdeProvider provider = providerWithStrictProfile();
            assertThrows(
                    JsonProfileConfigurationException.class,
                    () -> provider.deserializer(Counter.class, serdeConfigWith("does-not-exist")));
        }
    }

    // --- security: value-free deserialization-failure messages (W2) ---

    @Nested
    @DisplayName("deserialization-failure messages are value-free (W2 security fix)")
    class ValueFreeMessages {

        /**
         * Given a strict no-coercion profile and the payload {@code {"count":"5"}} (string where int
         * expected), when deserialization fails, then:
         * (a) {@link dev.vertique.kafka.DeserializationException#getMessage()} contains the target type
         *     name ({@code "Counter"}) but does NOT contain the offending value ({@code "5"}) or the
         *     raw record content;
         * (b) {@link dev.vertique.kafka.DeserializationException#getCause()} is the Jackson exception
         *     (debuggability preserved).
         */
        @Test
        @DisplayName(
                "deserializer_failureMessage_isValueFree: type name present, offending value absent, cause preserved")
        void deserializer_failureMessage_isValueFree() {
            JsonSerdeProvider provider = providerWithStrictProfile();
            KafkaDeserializer<Counter> strict = provider.deserializer(Counter.class, serdeConfigWith("no-coercion"));
            DeserializationException ex = assertThrows(
                    DeserializationException.class,
                    () -> strict.deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of()));

            // (a) message must reference the type name for operator context
            assertTrue(
                    ex.getMessage().contains("Counter"),
                    "message should contain the type name, got: " + ex.getMessage());
            // (a) message must NOT contain the offending value "5" (value-free contract)
            assertFalse(
                    ex.getMessage().contains("\"5\""),
                    "message must not contain the offending value, got: " + ex.getMessage());
            assertFalse(
                    ex.getMessage().contains(":\"5\""),
                    "message must not contain the raw JSON value, got: " + ex.getMessage());
            // (b) cause must be the Jackson exception for stacktrace debuggability
            assertNotNull(ex.getCause(), "cause must be preserved for debugging");
            assertInstanceOf(Exception.class, ex.getCause());
        }

        /**
         * Given the {@code routingDeserializer} path followed by {@code convertRouted} with a strict
         * no-coercion profile and the payload {@code {"count":"5"}}, when {@code convertRouted} fails,
         * then the {@link dev.vertique.kafka.DeserializationException#getMessage()} contains the target
         * type name but does NOT contain the offending value {@code "5"}, and the cause is preserved.
         */
        @Test
        @DisplayName(
                "convertRouted_failureMessage_isValueFree: type name present, offending value absent, cause preserved")
        void convertRouted_failureMessage_isValueFree() throws Exception {
            JsonSerdeProvider provider = providerWithStrictProfile();
            JsonObject strictBag = serdeConfigWith("no-coercion");

            // routingDeserializer succeeds (parses into a JsonNode tree) — only convertRouted rejects
            Object tree =
                    provider.routingDeserializer(strictBag).deserialize(bytes("{\"count\":\"5\"}"), "t", Map.of());
            assertNotNull(tree);

            DeserializationException ex = assertThrows(
                    DeserializationException.class, () -> provider.convertRouted(tree, Counter.class, strictBag));

            // (a) message must reference the type name
            assertTrue(
                    ex.getMessage().contains(Counter.class.getName()),
                    "message should contain the fully-qualified type name, got: " + ex.getMessage());
            // (a) message must NOT contain the offending value "5"
            assertFalse(
                    ex.getMessage().contains("\"5\""),
                    "message must not contain the offending value, got: " + ex.getMessage());
            assertFalse(
                    ex.getMessage().contains(":\"5\""),
                    "message must not contain the raw JSON value, got: " + ex.getMessage());
            // (b) cause preserved
            assertNotNull(ex.getCause(), "cause must be preserved for debugging");
        }
    }
}
