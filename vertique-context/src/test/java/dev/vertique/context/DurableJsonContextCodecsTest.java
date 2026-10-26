// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DurableJsonContextCodecs}.
 *
 * <p>Verifies JSON encoder/decoder factory methods using a small test envelope record and value
 * type. Covers namespace(), type(), round-trip encode/decode, absent namespace, and a body that
 * cannot be mapped to the envelope type.
 */
class DurableJsonContextCodecsTest {

    /** Simple envelope record used for JSON encode/decode tests. */
    record SampleEnvelope(String name, int n) {}

    private static final String NAMESPACE = "sample-ns";
    private static final DurableEncodeContext ENCODE_CTX = new DurableEncodeContext("test-boundary");
    private static final DurableDecodeContext DECODE_CTX = new DurableDecodeContext("test-boundary");

    // --- namespace ---

    @Test
    @DisplayName("jsonEncoder.namespace() returns the configured namespace")
    void encoderNamespaceReturnsExpected() {
        DurableContextMetadataEncoder<StringCtx> encoder =
                DurableJsonContextCodecs.jsonEncoder(StringCtx.class, NAMESPACE, v -> new SampleEnvelope(v.value(), 1));
        assertEquals(NAMESPACE, encoder.namespace());
    }

    @Test
    @DisplayName("jsonDecoder.namespace() returns the configured namespace")
    void decoderNamespaceReturnsExpected() {
        DurableContextMetadataDecoder<StringCtx> decoder = DurableJsonContextCodecs.jsonDecoder(
                StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> new StringCtx(e.name()));
        assertEquals(NAMESPACE, decoder.namespace());
    }

    // --- encode ---

    @Test
    @DisplayName("jsonEncoder.encode() produces a DurableMetadata whose namespace body contains the envelope fields")
    void encoderProducesJsonBody() {
        DurableContextMetadataEncoder<StringCtx> encoder = DurableJsonContextCodecs.jsonEncoder(
                StringCtx.class, NAMESPACE, v -> new SampleEnvelope(v.value(), 42));
        DurableMetadata encoded = encoder.encode(new StringCtx("hello"), ENCODE_CTX);
        assertTrue(encoded.has(NAMESPACE), "encoded metadata must contain the declared namespace");
        JsonObject body = encoded.body(NAMESPACE).orElseThrow();
        assertNotNull(body.getString("name"), "body must contain the name field");
        assertEquals("hello", body.getString("name"));
        assertEquals(42, body.getInteger("n"));
    }

    // --- round-trip ---

    @Test
    @DisplayName("encode + decode round-trip reproduces original value")
    void roundTripReproducesOriginal() {
        StringCtx original = new StringCtx("world");
        DurableContextMetadataEncoder<StringCtx> encoder = DurableJsonContextCodecs.jsonEncoder(
                StringCtx.class, NAMESPACE, v -> new SampleEnvelope(v.value(), 99));
        DurableContextMetadataDecoder<StringCtx> decoder = DurableJsonContextCodecs.jsonDecoder(
                StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> new StringCtx(e.name()));

        DurableMetadata encoded = encoder.encode(original, ENCODE_CTX);
        ContextDecodeResult<StringCtx> result = decoder.decode(encoded, DECODE_CTX);
        assertTrue(result.value().isPresent(), "round-trip must produce a value");
        assertEquals(original, result.value().get());
    }

    // --- decode: absent namespace ---

    @Test
    @DisplayName("jsonDecoder returns empty when namespace is absent from DurableMetadata")
    void decoderAbsentNamespaceReturnsEmpty() {
        DurableContextMetadataDecoder<StringCtx> decoder = DurableJsonContextCodecs.jsonDecoder(
                StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> new StringCtx(e.name()));
        ContextDecodeResult<StringCtx> result = decoder.decode(DurableMetadata.empty(), DECODE_CTX);
        assertTrue(result.value().isEmpty(), "absent namespace must produce empty result");
        assertTrue(result.warnings().isEmpty());
    }

    // --- decode: body that cannot map to envelope type ---

    @Test
    @DisplayName("jsonDecoder returns failure with warning when namespace body cannot map to envelope type")
    void decoderIncompatibleBodyReturnsFailure() {
        DurableContextMetadataDecoder<StringCtx> decoder = DurableJsonContextCodecs.jsonDecoder(
                StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> new StringCtx(e.name()));
        // Provide a body with a field of the wrong type (n is an int but we supply a non-numeric string)
        // so mapTo(SampleEnvelope.class) throws.
        DurableMetadata badMetadata =
                DurableMetadata.of(NAMESPACE, new JsonObject().put("name", "ok").put("n", "not-a-number"));
        ContextDecodeResult<StringCtx> result = decoder.decode(badMetadata, DECODE_CTX);
        assertTrue(result.value().isEmpty(), "incompatible body must produce empty value");
        assertEquals(1, result.warnings().size());
        ContextDecodeWarning warning = result.warnings().get(0);
        assertEquals(NAMESPACE, warning.key());
        assertNotNull(warning.reason(), "warning reason must not be null");
        assertTrue(
                warning.reason().contains("Failed to decode JSON envelope"),
                "warning reason must mention decode failure; got: " + warning.reason());
    }

    // --- decode: restoreFn that throws ---

    @Test
    @DisplayName("jsonDecoder returns failure with warning when restoreFn throws")
    void decoderRestoreFnThrowsReturnsFailure() {
        DurableContextMetadataDecoder<StringCtx> decoder =
                DurableJsonContextCodecs.jsonDecoder(StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> {
                    throw new RuntimeException("restoreFn simulated failure");
                });
        DurableMetadata metadata =
                DurableMetadata.of(NAMESPACE, new JsonObject().put("name", "ok").put("n", 1));
        ContextDecodeResult<StringCtx> result = decoder.decode(metadata, DECODE_CTX);
        assertTrue(result.value().isEmpty(), "restoreFn failure must produce empty value");
        assertEquals(1, result.warnings().size());
        assertTrue(
                result.warnings().get(0).reason().contains("Failed to decode JSON envelope"),
                "warning must mention decode failure");
    }

    @Test
    @DisplayName("boundary-aware factories copy allowlists and filter before mapping")
    @SuppressWarnings("unchecked")
    void boundaryAwareFactoriesFilterBeforeMapping() throws Exception {
        Method encoderFactory = assertDoesNotThrow(
                () -> DurableJsonContextCodecs.class.getMethod(
                        "jsonEncoder", Class.class, String.class, Set.class, Function.class),
                "boundary-aware encoder factory overload must exist");
        Method decoderFactory = assertDoesNotThrow(
                () -> DurableJsonContextCodecs.class.getMethod(
                        "jsonDecoder", Class.class, String.class, Class.class, Set.class, Function.class),
                "boundary-aware envelope decoder factory overload must exist");

        AtomicInteger encoded = new AtomicInteger();
        Set<String> producerBoundaries = new HashSet<>(Set.of("producer"));
        DurableContextMetadataEncoder<StringCtx> encoder =
                (DurableContextMetadataEncoder<StringCtx>) encoderFactory.invoke(
                        null, StringCtx.class, NAMESPACE, producerBoundaries, (Function<StringCtx, SampleEnvelope>)
                                value -> {
                                    encoded.incrementAndGet();
                                    return new SampleEnvelope(value.value(), 1);
                                });
        producerBoundaries.clear();
        assertEquals(
                DurableMetadata.empty(), encoder.encode(new StringCtx("blocked"), new DurableEncodeContext("other")));
        assertEquals(0, encoded.get(), "an excluded boundary must not invoke the mapper");
        assertEquals(
                "allowed",
                encoder.encode(new StringCtx("allowed"), new DurableEncodeContext("producer"))
                        .body(NAMESPACE)
                        .orElseThrow()
                        .getString("name"));
        assertEquals(1, encoded.get());

        AtomicInteger restored = new AtomicInteger();
        Set<String> receiverBoundaries = new HashSet<>(Set.of("receiver"));
        DurableContextMetadataDecoder<StringCtx> decoder =
                (DurableContextMetadataDecoder<StringCtx>) decoderFactory.invoke(
                        null, StringCtx.class, NAMESPACE, SampleEnvelope.class, receiverBoundaries, (Function<
                                        SampleEnvelope, StringCtx>)
                                envelope -> {
                                    restored.incrementAndGet();
                                    return new StringCtx(envelope.name());
                                });
        receiverBoundaries.clear();
        DurableMetadata malformed = DurableMetadata.of(NAMESPACE, new JsonObject().put("n", "not-a-number"));
        assertEquals(ContextDecodeResult.empty(), decoder.decode(malformed, new DurableDecodeContext("other")));
        assertEquals(0, restored.get(), "an excluded boundary must not map or restore the body");
        assertEquals(
                new StringCtx("allowed"),
                decoder.decode(
                                DurableMetadata.of(
                                        NAMESPACE,
                                        new JsonObject().put("name", "allowed").put("n", 1)),
                                new DurableDecodeContext("receiver"))
                        .value()
                        .orElseThrow());
        assertEquals(1, restored.get());

        DurableContextMetadataEncoder<StringCtx> denyAll =
                (DurableContextMetadataEncoder<StringCtx>) encoderFactory.invoke(
                        null, StringCtx.class, NAMESPACE, Set.of(), (Function<StringCtx, SampleEnvelope>) value -> {
                            encoded.incrementAndGet();
                            return new SampleEnvelope(value.value(), 1);
                        });
        assertEquals(
                DurableMetadata.empty(), denyAll.encode(new StringCtx("no"), new DurableEncodeContext("producer")));
        assertEquals(1, encoded.get(), "an empty allowlist must deny every boundary before mapping");
    }

    @Test
    @DisplayName("raw-object decoder callback runs only for a present body at an allowed boundary")
    @SuppressWarnings("unchecked")
    void rawObjectDecoderReturnsCallbackResult() throws Exception {
        Method decoderFactory = assertDoesNotThrow(
                () -> DurableJsonContextCodecs.class.getMethod(
                        "jsonDecoder", Class.class, String.class, Set.class, Function.class),
                "raw-object decoder factory overload must exist");
        AtomicInteger calls = new AtomicInteger();
        DurableContextMetadataDecoder<StringCtx> decoder = (DurableContextMetadataDecoder<StringCtx>)
                decoderFactory.invoke(null, StringCtx.class, NAMESPACE, Set.of("receiver"), (Function<
                                JsonObject, ContextDecodeResult<StringCtx>>)
                        body -> {
                            calls.incrementAndGet();
                            return body.getString("name") == null
                                    ? ContextDecodeResult.failure(java.util.List.of(
                                            new ContextDecodeWarning(NAMESPACE, null, "name-required")))
                                    : ContextDecodeResult.of(new StringCtx(body.getString("name")));
                        });

        DurableMetadata present = DurableMetadata.of(NAMESPACE, new JsonObject().put("name", "raw"));
        assertEquals(
                ContextDecodeResult.empty(),
                decoder.decode(DurableMetadata.empty(), new DurableDecodeContext("receiver")));
        assertEquals(ContextDecodeResult.empty(), decoder.decode(present, new DurableDecodeContext("other")));
        assertEquals(0, calls.get(), "absent and excluded inputs must not invoke the callback");
        assertEquals(
                new StringCtx("raw"),
                decoder.decode(present, new DurableDecodeContext("receiver"))
                        .value()
                        .orElseThrow());
        assertEquals(1, calls.get());
        assertEquals(
                new ContextDecodeWarning(NAMESPACE, null, "name-required"),
                decoder.decode(DurableMetadata.of(NAMESPACE, new JsonObject()), new DurableDecodeContext("receiver"))
                        .warnings()
                        .getFirst());
        assertEquals(2, calls.get());
    }

    // --- type ---

    @Test
    @DisplayName("jsonEncoder.type() returns the configured type")
    void encoderReturnsConfiguredType() {
        DurableContextMetadataEncoder<StringCtx> encoder =
                DurableJsonContextCodecs.jsonEncoder(StringCtx.class, NAMESPACE, v -> new SampleEnvelope(v.value(), 0));
        assertEquals(StringCtx.class, encoder.type());
    }

    @Test
    @DisplayName("jsonDecoder.type() returns the configured type")
    void decoderReturnsConfiguredType() {
        DurableContextMetadataDecoder<StringCtx> decoder = DurableJsonContextCodecs.jsonDecoder(
                StringCtx.class, NAMESPACE, SampleEnvelope.class, e -> new StringCtx(e.name()));
        assertEquals(StringCtx.class, decoder.type());
    }
}
