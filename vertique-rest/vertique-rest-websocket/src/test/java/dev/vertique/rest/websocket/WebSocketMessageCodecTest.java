// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JacksonDefaults;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketMessageCodec}, verifying JSON encode/decode round-trips
 * and error handling for invalid JSON input.
 *
 * <p>TP-001 and TP-003 (T024) additionally prove {@link WebSocketMessageCodec#decode} resolves
 * {@link VertiqueJson#mapper()} at use time — not the raw, install-blind
 * {@code io.vertx.core.json.jackson.DatabindCodec#mapper()} — by installing a mapper
 * <strong>after</strong> the codec under test is constructed and observing that the install is
 * visible to the very next decode. {@code @AfterEach} restores the raw Vert.x delegate so the
 * process-wide install never leaks across tests (the reset seam is opened by this module's
 * {@code <build>} argLine, {@code -Dvertique.json.codec.allowReset=true}).
 */
@DisplayName("WebSocketMessageCodec")
class WebSocketMessageCodecTest {

    /** Simple test payload used across encode/decode tests. */
    record TestMessage(String text, int count) {}

    /** TP-001 fixture: an {@link Optional} component, unsupported by Jackson without {@code Jdk8Module}. */
    record OptionalPayload(Optional<String> value) {}

    /** TP-003 fixture enum: {@code UNKNOWN} is the {@code @JsonEnumDefaultValue} fallback constant. */
    enum Status {
        @JsonEnumDefaultValue
        UNKNOWN,
        ACTIVE
    }

    /** TP-003 fixture: an enum component that can carry an unrecognized wire value. */
    record StatusPayload(Status status) {}

    private WebSocketMessageCodec codec;

    @BeforeEach
    void setUp() {
        codec = new WebSocketMessageCodec();
    }

    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- TP-001 (T024): decode() resolves the process mapper at use time ---

    /**
     * TP-001: {@code new WebSocketMessageCodec()} is constructed <strong>before</strong>
     * {@link VertiqueJson#install}. Decoding a payload with an {@link Optional} component
     * <strong>after</strong> the install must succeed — proof that {@link WebSocketMessageCodec#decode}
     * reads {@link VertiqueJson#mapper()} live, at decode time, rather than a raw mapper resolved
     * once and cached.
     *
     * <p>Given: the codec constructed before {@code VertiqueJson.install(JsonProfileId.of("test-jdk8"),
     * seedMapper)}, where {@code seedMapper} is the sanctioned seed
     * ({@code JacksonDefaults.applySystem(new ObjectMapper())}: {@code Jdk8Module} and
     * {@code VertxModule} registered).
     * When: the codec decodes a message record with an {@code Optional<String>} component after the
     * install.
     * Then: decoding succeeds and the optional value binds.
     *
     * <p><strong>Expected initial (RED) result:</strong> today's {@code decode} reads
     * {@code DatabindCodec.mapper()} directly — the raw Vert.x mapper has no {@code Jdk8Module}
     * registered, so binding {@code Optional<String>} throws {@code InvalidDefinitionException}
     * regardless of the install.
     *
     * <p><strong>Sensitivity proof:</strong> keeping {@code DatabindCodec.mapper()} in the codec fails
     * this assertion even after the install — only reading {@link VertiqueJson#mapper()} makes it pass.
     */
    @Test
    @DisplayName("decodesWithTheInstalledProcessMapper: decode() resolves VertiqueJson.mapper() at use time"
            + " (Optional component binds only through the installed mapper)")
    void decodesWithTheInstalledProcessMapper() throws JsonProcessingException {
        WebSocketMessageCodec installedCodec = new WebSocketMessageCodec();

        ObjectMapper seedMapper = JacksonDefaults.applySystem(new ObjectMapper());
        VertiqueJson.install(JsonProfileId.of("test-jdk8"), seedMapper);

        OptionalPayload decoded = installedCodec.decode("{\"value\":\"present\"}", OptionalPayload.class);

        assertEquals(Optional.of("present"), decoded.value());
    }

    // --- TP-003 (T024): unknown enum binds to the default only under the installed lenient mapper ---

    /**
     * TP-003: proves {@link WebSocketMessageCodec#decode} is binder-authoritative for enum leniency —
     * an unknown enum string is rejected under the raw delegate but binds to the
     * {@code @JsonEnumDefaultValue} constant once the registry's {@code vertique} mapper (which enables
     * {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}) is installed as the process codec.
     *
     * <p>Given: a message record with an enum component carrying {@code @JsonEnumDefaultValue}; the raw
     * delegate, then {@code new DefaultJsonMapperProfileRegistry(Set.of()).mapper(JsonProfileId.of("vertique"))}
     * installed under {@code "vertique"}.
     * When: a payload with an unknown enum string is decoded in both states.
     * Then: rejected under the raw delegate; bound to the default under {@code vertique}.
     *
     * <p><strong>Expected initial (RED) result:</strong> today's {@code decode} always reads
     * {@code DatabindCodec.mapper()}, so the second decode is rejected exactly like the first — the
     * install has no effect.
     *
     * <p><strong>Sensitivity proof:</strong> keeping {@code DatabindCodec.mapper()} in the codec leaves
     * the second decode rejected — only reading {@link VertiqueJson#mapper()} makes it bind to the
     * default.
     */
    @Test
    @DisplayName("unknownEnumBindsToDefaultOnlyUnderLenientProcessMapper: decode() resolves"
            + " VertiqueJson.mapper() at use time (unknown enum binds to @JsonEnumDefaultValue only under"
            + " the installed vertique mapper)")
    void unknownEnumBindsToDefaultOnlyUnderLenientProcessMapper() throws JsonProcessingException {
        String payload = "{\"status\":\"BOGUS\"}";

        assertThrows(
                JsonProcessingException.class,
                () -> codec.decode(payload, StatusPayload.class),
                "the raw delegate has no READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE leniency");

        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        VertiqueJson.install(JsonProfileId.of("vertique"), registry.mapper(JsonProfileId.of("vertique")));

        StatusPayload decoded = codec.decode(payload, StatusPayload.class);

        assertEquals(Status.UNKNOWN, decoded.status());
    }

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        @DisplayName("encodes POJO to JSON string")
        void encodesPojoToJson() throws JsonProcessingException {
            TestMessage msg = new TestMessage("hello", 42);
            String json = codec.encode(msg);
            assertNotNull(json);
            // Verify the JSON contains the expected fields
            assertEquals("{\"text\":\"hello\",\"count\":42}", json);
        }

        @Test
        @DisplayName("encodes null-containing field to JSON with null value")
        void encodesNullField() throws JsonProcessingException {
            TestMessage msg = new TestMessage(null, 0);
            String json = codec.encode(msg);
            assertNotNull(json);
            assertEquals("{\"text\":null,\"count\":0}", json);
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        @DisplayName("decodes JSON string to POJO")
        void decodesJsonToPojo() throws JsonProcessingException {
            String json = "{\"text\":\"world\",\"count\":7}";
            TestMessage msg = codec.decode(json, TestMessage.class);
            assertNotNull(msg);
            assertEquals("world", msg.text());
            assertEquals(7, msg.count());
        }

        @Test
        @DisplayName("decode of invalid JSON throws JsonProcessingException")
        void invalidJsonThrows() {
            assertThrows(JsonProcessingException.class, () -> codec.decode("not-valid-json", TestMessage.class));
        }

        @Test
        @DisplayName("decode of empty object produces default-valued record")
        void emptyObjectProducesDefaults() throws JsonProcessingException {
            String json = "{}";
            TestMessage msg = codec.decode(json, TestMessage.class);
            assertNotNull(msg);
            assertNull(msg.text());
            assertEquals(0, msg.count());
        }

        /** Convenience null assertion — avoids importing Assertions on every use. */
        private void assertNull(Object o) {
            org.junit.jupiter.api.Assertions.assertNull(o);
        }
    }

    @Nested
    @DisplayName("encode → decode round-trip")
    class RoundTrip {

        @Test
        @DisplayName("round-trip preserves POJO equality")
        void roundTripPreservesEquality() throws JsonProcessingException {
            TestMessage original = new TestMessage("round-trip", 99);
            String json = codec.encode(original);
            TestMessage decoded = codec.decode(json, TestMessage.class);
            assertEquals(original, decoded);
        }
    }
}
