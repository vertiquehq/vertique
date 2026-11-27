// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonCodecException;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Behavior of {@link Jackson2JsonProfileCodec}. */
class Jackson2JsonProfileCodecTest {

    private static final JsonProfileId ID = JsonProfileId.of("codec-test");

    public record Rec(String firstName, int count) {}

    private static ObjectMapper strictSnakeMapper() {
        return new ObjectMapper()
                .registerModule(VertxJsonSupport.module())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static Jackson2JsonProfileCodec codecOver(ObjectMapper mapper) {
        return Jackson2JsonProfiles.of(ID, mapper);
    }

    @Test
    @DisplayName("encode, decode and convert equal the mapper's own results, parameterized types included")
    void operations_equalTheMappersOwnResults() throws Exception {
        // Given: a mapper with SNAKE_CASE and FAIL_ON_UNKNOWN_PROPERTIES and a codec over it.
        ObjectMapper mapper = strictSnakeMapper();
        Jackson2JsonProfileCodec codec = codecOver(mapper);
        Rec rec = new Rec("ada", 3);
        List<Rec> recs = List.of(rec, new Rec("bob", 4));
        Type listType = new TypeReference<List<Rec>>() {}.getType();

        // When/Then: encode forms match the mapper.
        assertArrayEquals(mapper.writeValueAsBytes(rec), codec.encode(rec).getBytes());
        assertEquals(mapper.writeValueAsString(rec), codec.encodeToString(rec));
        assertEquals("{\"first_name\":\"ada\",\"count\":3}", codec.encodeToString(rec));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.encodeTo(recs, out);
        assertArrayEquals(mapper.writeValueAsBytes(recs), out.toByteArray());

        // decode from text and Buffer, class and parameterized type.
        String json = mapper.writeValueAsString(recs);
        assertEquals(rec, codec.decode("{\"first_name\":\"ada\",\"count\":3}", Rec.class));
        assertEquals(rec, codec.decode(Buffer.buffer("{\"first_name\":\"ada\",\"count\":3}"), Rec.class));
        List<Rec> decoded = codec.decode(json, listType);
        assertEquals(mapper.readValue(json, new TypeReference<List<Rec>>() {}), decoded);
        assertEquals(recs, decoded);
        List<Rec> decodedBuffer = codec.decode(Buffer.buffer(json), listType);
        assertEquals(recs, decodedBuffer);

        // convert matches convertValue, parameterized included.
        Object generic = mapper.readValue(json, Object.class);
        List<Rec> converted = codec.convert(generic, listType);
        assertEquals(mapper.convertValue(generic, new TypeReference<List<Rec>>() {}), converted);
        assertEquals(rec, codec.convert(Map.of("first_name", "ada", "count", 3), Rec.class));

        // unknown properties still fail (the mapper's flag is honoured).
        assertThrows(JsonCodecException.class, () -> codec.decode("{\"first_name\":\"a\",\"zzz\":1}", Rec.class));
    }

    @Test
    @DisplayName("unwrap returns the same mapper instance on every call, and empty for other types")
    void unwrap_returnsSameMapper_andEmptyForOtherTypes() {
        ObjectMapper mapper = strictSnakeMapper();
        Jackson2JsonProfileCodec codec = codecOver(mapper);

        assertSame(mapper, codec.unwrap(ObjectMapper.class).orElseThrow());
        assertSame(mapper, codec.unwrap(ObjectMapper.class).orElseThrow());
        assertEquals(Optional.empty(), codec.unwrap(String.class));
    }

    @Test
    @DisplayName("encodeTo leaves the target stream open and unflushed")
    void encodeTo_leavesStreamOpenAndUnflushed() {
        TrackingStream out = new TrackingStream();

        codecOver(strictSnakeMapper()).encodeTo(new Rec("ada", 1), out);

        assertFalse(out.closed, "encodeTo must not close the stream");
        assertEquals(0, out.flushes, "encodeTo must not flush the stream");
        assertTrue(out.size() > 0, "the value must have been written");
    }

    @Test
    @DisplayName("objectMapper of a profile without a mapper names the profile id")
    void objectMapper_withoutMapper_throwsNamingTheId() {
        JsonProfileId id = JsonProfileId.of("no-mapper");
        JsonProfileCodec noMapper = new DirectCodec(id);

        JsonProfileConfigurationException ex = assertThrows(
                JsonProfileConfigurationException.class, () -> Jackson2JsonProfileCodec.objectMapper(noMapper));

        assertTrue(ex.getMessage().contains("no-mapper"), ex.getMessage());
    }

    @Test
    @DisplayName("objectMapper of a Jackson-backed profile returns its mapper")
    void objectMapper_ofJacksonProfile_returnsMapper() {
        ObjectMapper mapper = strictSnakeMapper();

        assertSame(mapper, Jackson2JsonProfileCodec.objectMapper(codecOver(mapper)));
    }

    @Test
    @DisplayName(
            "a subclass returning a new mapper per call is captured once; every operation and unwrap use the first")
    void capturedMapper_isUsedForEveryOperationAndUnwrap() {
        AtomicInteger calls = new AtomicInteger();
        List<ObjectMapper> created = new java.util.ArrayList<>();
        Jackson2JsonProfileCodec codec = new Jackson2JsonProfileCodec() {
            @Override
            public JsonProfileId id() {
                return ID;
            }

            // public: the interface still declares a public mapper() in the stub phase; public compiles before and
            // after.
            @Override
            public ObjectMapper mapper() {
                calls.incrementAndGet();
                ObjectMapper m = strictSnakeMapper();
                created.add(m);
                return m;
            }
        };

        codec.encode(new Rec("a", 1));
        codec.encodeToString(new Rec("a", 1));
        codec.encodeTo(new Rec("a", 1), new ByteArrayOutputStream());
        codec.decode("{\"first_name\":\"a\",\"count\":1}", Rec.class);
        codec.decode(Buffer.buffer("{\"first_name\":\"a\",\"count\":1}"), Rec.class);
        codec.convert(Map.of("first_name", "a", "count", 1), Rec.class);
        ObjectMapper first = codec.unwrap(ObjectMapper.class).orElseThrow();
        ObjectMapper second = codec.unwrap(ObjectMapper.class).orElseThrow();

        assertEquals(1, calls.get(), "mapper() must be called exactly once");
        assertSame(first, second);
        assertSame(created.get(0), first);
    }

    @Test
    @DisplayName("trailing content is rejected even when the mapper does not fail on trailing tokens")
    void trailingContent_isRejected() {
        ObjectMapper mapper = DatabindCodec.mapper();
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        Jackson2JsonProfileCodec codec = codecOver(mapper);

        assertThrows(JsonCodecException.class, () -> codec.decode("{\"a\":1} x", Rec.class));
        assertThrows(JsonCodecException.class, () -> codec.decode("{} {}", Object.class));
        assertThrows(JsonCodecException.class, () -> codec.decode(Buffer.buffer("{} {}"), Object.class));
        assertFalse(
                mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                "the shared mapper must not be reconfigured");
    }

    @Test
    @DisplayName("Object targets yield JsonObject and JsonArray")
    void objectTargets_yieldVertxTypes() {
        Jackson2JsonProfileCodec codec = codecOver(DatabindCodec.mapper());

        assertInstanceOf(JsonObject.class, codec.decode("{}", Object.class));
        assertInstanceOf(JsonArray.class, codec.decode("[]", Object.class));
        assertInstanceOf(JsonObject.class, codec.decode(Buffer.buffer("{}"), Object.class));
        assertInstanceOf(JsonObject.class, codec.convert(Map.of(), Object.class));
        assertInstanceOf(JsonArray.class, codec.convert(List.of(), Object.class));

        ObjectMapper arrays = new ObjectMapper()
                .registerModule(VertxJsonSupport.module())
                .enable(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY);
        assertInstanceOf(JsonArray.class, codecOver(arrays).decode("[1]", Object.class));
    }

    @Test
    @DisplayName("malformed input fails with a fixed message, no input text, original cause")
    void decodeFailure_hasFixedMessageAndOriginalCause() {
        Jackson2JsonProfileCodec codec = codecOver(strictSnakeMapper());
        String secret = "hunter2-S3CR3T";

        JsonCodecException ex = assertThrows(
                JsonCodecException.class, () -> codec.decode("{\"pw\":\"" + secret + "\" oops", Rec.class));
        JsonCodecException exBuffer = assertThrows(
                JsonCodecException.class,
                () -> codec.decode(Buffer.buffer("{\"pw\":\"" + secret + "\" oops"), Rec.class));

        for (JsonCodecException e : List.of(ex, exBuffer)) {
            assertEquals("JSON decoding failed", e.getMessage());
            assertFalse(String.valueOf(e.getMessage()).contains(secret));
            assertInstanceOf(JsonProcessingException.class, e.getCause());
        }
    }

    @Test
    @DisplayName("an incompatible conversion fails with a fixed message and the original cause")
    void convertFailure_hasFixedMessageAndOriginalCause() {
        Jackson2JsonProfileCodec codec = codecOver(strictSnakeMapper());
        String secret = "hunter2-S3CR3T";

        JsonCodecException ex = assertThrows(
                JsonCodecException.class, () -> codec.convert(Map.of("count", secret, "first_name", "a"), Rec.class));

        assertEquals("JSON conversion failed", ex.getMessage());
        assertFalse(ex.getMessage().contains(secret));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @DisplayName("a serializer that throws fails with a fixed message and the original cause")
    void encodeFailure_hasFixedMessageAndOriginalCause() {
        IOException boom = new IOException("boom-S3CR3T");
        SimpleModule module = new SimpleModule();
        module.addSerializer(Rec.class, new JsonSerializer<Rec>() {
            @Override
            public void serialize(Rec value, com.fasterxml.jackson.core.JsonGenerator gen, SerializerProvider p)
                    throws IOException {
                throw boom;
            }
        });
        Jackson2JsonProfileCodec codec = codecOver(new ObjectMapper().registerModule(module));
        Rec rec = new Rec("a", 1);

        JsonCodecException a = assertThrows(JsonCodecException.class, () -> codec.encode(rec));
        JsonCodecException b = assertThrows(JsonCodecException.class, () -> codec.encodeToString(rec));
        JsonCodecException c =
                assertThrows(JsonCodecException.class, () -> codec.encodeTo(rec, new ByteArrayOutputStream()));

        for (JsonCodecException e : List.of(a, b, c)) {
            assertEquals("JSON encoding failed", e.getMessage());
            assertFalse(e.getMessage().contains("S3CR3T"));
            assertInstanceOf(IOException.class, e.getCause());
            assertTrue(e.getCause() == boom
                    || e.getCause().getCause() == boom
                    || e.getCause() instanceof JsonProcessingException);
        }
    }

    private static final class TrackingStream extends ByteArrayOutputStream {
        boolean closed;
        int flushes;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        @Override
        public void flush() throws IOException {
            flushes++;
            super.flush();
        }
    }

    /** A direct implementation that is not a {@link Jackson2JsonProfileCodec} and exposes no mapper. */
    private static final class DirectCodec implements JsonProfileCodec {
        private final JsonProfileId id;

        DirectCodec(JsonProfileId id) {
            this.id = id;
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        public Buffer encode(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String encodeToString(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void encodeTo(Object value, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(Buffer json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T decode(String json, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convert(Object value, Type type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> type) {
            return Optional.empty();
        }

        // Present only while the baseline interface still declares it; no @Override on purpose.
        public ObjectMapper mapper() {
            return null;
        }
    }
}
