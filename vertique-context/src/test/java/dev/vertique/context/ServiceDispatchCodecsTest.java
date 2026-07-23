// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDispatchCodecs}.
 *
 * <p>Verifies snapshot encoder/decoder and pass-through encoder/decoder factory methods including
 * happy paths, null rejection (FR-CTX-050), type mismatch failure results, and empty results on
 * null input.
 */
class ServiceDispatchCodecsTest {

    private static final ServiceDispatchDecodeContext DECODE_CTX = new ServiceDispatchDecodeContext("test-boundary");
    private static final ServiceDispatchEncodeContext ENCODE_CTX = new ServiceDispatchEncodeContext("test-boundary");

    // --- snapshotEncoder ---

    @Test
    @DisplayName("snapshotEncoder.type() returns the configured type")
    void snapshotEncoderReturnsConfiguredType() {
        ServiceDispatchContextEncoder<StringCtx> encoder = ServiceDispatchCodecs.snapshotEncoder(
                StringCtx.class, sc -> sc.value().toUpperCase());
        assertEquals(StringCtx.class, encoder.type());
    }

    @Test
    @DisplayName("snapshotEncoder.encode() returns result of snapshot function")
    void snapshotEncoderReturnsSnapshotFnResult() {
        ServiceDispatchContextEncoder<StringCtx> encoder =
                ServiceDispatchCodecs.snapshotEncoder(StringCtx.class, sc -> "SNAP:" + sc.value());
        Object result = encoder.encode(new StringCtx("hello"), ENCODE_CTX);
        assertEquals("SNAP:hello", result);
    }

    @Test
    @DisplayName("snapshotEncoder.encode() throws NPE when snapshotFn returns null (FR-CTX-050)")
    void snapshotEncoderNullResultThrowsNpe() {
        ServiceDispatchContextEncoder<StringCtx> encoder =
                ServiceDispatchCodecs.snapshotEncoder(StringCtx.class, sc -> null);
        assertThrows(NullPointerException.class, () -> encoder.encode(new StringCtx("hello"), ENCODE_CTX));
    }

    // --- snapshotDecoder ---

    @Test
    @DisplayName("snapshotDecoder.decode(null) returns empty result")
    void snapshotDecoderNullValueReturnsEmpty() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.snapshotDecoder(
                StringCtx.class, String.class, s -> new StringCtx(s.toLowerCase()));
        ContextDecodeResult<StringCtx> result = decoder.decode(null, DECODE_CTX);
        assertTrue(result.value().isEmpty(), "null input must produce empty result");
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("snapshotDecoder.decode(snapshot) returns of(restored) when type matches")
    void snapshotDecoderHappyPath() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.snapshotDecoder(
                StringCtx.class, String.class, s -> new StringCtx("RESTORED:" + s));
        ContextDecodeResult<StringCtx> result = decoder.decode("snap", DECODE_CTX);
        assertTrue(result.value().isPresent());
        assertEquals(new StringCtx("RESTORED:snap"), result.value().get());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("snapshotDecoder.decode(wrongType) returns failure with descriptive warning")
    void snapshotDecoderWrongTypeReturnsFailure() {
        ServiceDispatchContextDecoder<StringCtx> decoder =
                ServiceDispatchCodecs.snapshotDecoder(StringCtx.class, String.class, s -> new StringCtx(s));
        // Pass an Integer where String (snapshot type) is expected
        ContextDecodeResult<StringCtx> result = decoder.decode(42, DECODE_CTX);
        assertTrue(result.value().isEmpty(), "wrong type must produce empty value");
        assertEquals(1, result.warnings().size());
        String reason = result.warnings().get(0).reason();
        assertTrue(
                reason.contains(String.class.getName()), "warning reason must reference expected type; got: " + reason);
        assertTrue(
                reason.contains(Integer.class.getName()), "warning reason must reference actual type; got: " + reason);
    }

    @Test
    @DisplayName("snapshotDecoder.decode() throws NPE when restoreFn returns null")
    void snapshotDecoderNullRestoreThrowsNpe() {
        ServiceDispatchContextDecoder<StringCtx> decoder =
                ServiceDispatchCodecs.snapshotDecoder(StringCtx.class, String.class, s -> null);
        assertThrows(NullPointerException.class, () -> decoder.decode("snap", DECODE_CTX));
    }

    @Test
    @DisplayName("snapshotDecoder.type() returns the configured type")
    void snapshotDecoderReturnsConfiguredType() {
        ServiceDispatchContextDecoder<StringCtx> decoder =
                ServiceDispatchCodecs.snapshotDecoder(StringCtx.class, String.class, s -> new StringCtx(s));
        assertEquals(StringCtx.class, decoder.type());
    }

    // --- passThroughEncoder ---

    @Test
    @DisplayName("passThroughEncoder.encode() returns the value unchanged (by reference)")
    void passThroughEncoderReturnsValueUnchanged() {
        ServiceDispatchContextEncoder<StringCtx> encoder = ServiceDispatchCodecs.passThroughEncoder(StringCtx.class);
        StringCtx value = new StringCtx("immutable-value");
        Object result = encoder.encode(value, ENCODE_CTX);
        assertSame(value, result, "pass-through encoder must return the original reference");
    }

    @Test
    @DisplayName("passThroughEncoder.type() returns the configured type")
    void passThroughEncoderReturnsConfiguredType() {
        ServiceDispatchContextEncoder<StringCtx> encoder = ServiceDispatchCodecs.passThroughEncoder(StringCtx.class);
        assertEquals(StringCtx.class, encoder.type());
    }

    // --- passThroughDecoder ---

    @Test
    @DisplayName("passThroughDecoder.decode(null) returns empty result")
    void passThroughDecoderNullValueReturnsEmpty() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.passThroughDecoder(StringCtx.class);
        ContextDecodeResult<StringCtx> result = decoder.decode(null, DECODE_CTX);
        assertTrue(result.value().isEmpty());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("passThroughDecoder.decode(value) returns of(value) when type matches")
    void passThroughDecoderHappyPath() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.passThroughDecoder(StringCtx.class);
        ContextDecodeResult<StringCtx> result = decoder.decode(new StringCtx("hello"), DECODE_CTX);
        assertTrue(result.value().isPresent());
        assertEquals(new StringCtx("hello"), result.value().get());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    @DisplayName("passThroughDecoder.decode(wrongType) returns failure with warning")
    void passThroughDecoderWrongTypeReturnsFailure() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.passThroughDecoder(StringCtx.class);
        ContextDecodeResult<StringCtx> result = decoder.decode(123, DECODE_CTX);
        assertTrue(result.value().isEmpty());
        assertEquals(1, result.warnings().size());
        String reason = result.warnings().get(0).reason();
        assertTrue(
                reason.contains(StringCtx.class.getName()),
                "warning reason must reference expected type; got: " + reason);
        assertTrue(
                reason.contains(Integer.class.getName()), "warning reason must reference actual type; got: " + reason);
    }

    @Test
    @DisplayName("passThroughDecoder.type() returns the configured type")
    void passThroughDecoderReturnsConfiguredType() {
        ServiceDispatchContextDecoder<StringCtx> decoder = ServiceDispatchCodecs.passThroughDecoder(StringCtx.class);
        assertEquals(StringCtx.class, decoder.type());
    }
}
