// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the helper-based service-dispatch propagation registered in
 * {@link CorrelationContextModule}.
 *
 * <p>Verifies that the encoder built via {@link ServiceDispatchCodecs#snapshotEncoder} captures a
 * live {@link CorrelationContext} as an immutable {@link CorrelationContextSnapshot}, and that
 * the matching decoder rebuilds a fresh {@link MutableCorrelationContext} via
 * {@link CorrelationContextFactory#fromSnapshot}.
 *
 * <p>The {@code SnapshotEncoder} returned by the helper is keyed by {@code CorrelationContext.class}
 * and its key is {@code CorrelationContext.class.getName()} — matches the holder bind key.
 */
class CorrelationServiceDispatchPropagationTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "http-header");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "http-header");

    private static final ServiceDispatchEncodeContext ENCODE_CTX = new ServiceDispatchEncodeContext("service-dispatch");
    private static final ServiceDispatchDecodeContext DECODE_CTX = new ServiceDispatchDecodeContext("service-dispatch");

    private final ServiceDispatchContextEncoder<CorrelationContext> encoder =
            ServiceDispatchCodecs.snapshotEncoder(CorrelationContext.class, CorrelationContext::snapshot);

    private final CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());

    private final ServiceDispatchContextDecoder<CorrelationContext> decoder = ServiceDispatchCodecs.snapshotDecoder(
            CorrelationContext.class, CorrelationContextSnapshot.class, factory::fromSnapshot);

    @Test
    @DisplayName("encoder.type() and decoder.type() return the public CorrelationContext interface")
    void typeKeysMatchInterface() {
        assertEquals(CorrelationContext.class, encoder.type());
        assertEquals(CorrelationContext.class, decoder.type());
    }

    @Test
    @DisplayName("encoder snapshots the live mutable context to an immutable CorrelationContextSnapshot")
    void encoderSnapshotsLiveValue() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.putAttribute("k", "v");

        Object carrier = encoder.encode(live, ENCODE_CTX);
        assertInstanceOf(CorrelationContextSnapshot.class, carrier);
        CorrelationContextSnapshot snap = (CorrelationContextSnapshot) carrier;
        assertEquals(REQ_ID, snap.requestId());
        assertEquals(CORR_ID, snap.correlationId());
        assertEquals("v", snap.attributes().get("k"));

        // Subsequent mutation on the live context must NOT bleed into the captured snapshot.
        live.putAttribute("k", "v2");
        assertEquals("v", snap.attributes().get("k"));
    }

    @Test
    @DisplayName("decoder rebuilds an independent live context from a snapshot")
    void decoderRebuildsIndependentLive() {
        CorrelationContextSnapshot snap = CorrelationContextSnapshot.of(REQ_ID, CORR_ID);
        ContextDecodeResult<CorrelationContext> result = decoder.decode(snap, DECODE_CTX);

        assertTrue(result.value().isPresent());
        CorrelationContext rebuilt = result.value().get();
        assertNotSame(snap, rebuilt);
        assertEquals(REQ_ID, rebuilt.requestId());
        assertEquals(CORR_ID, rebuilt.correlationId());
        assertTrue(result.warnings().isEmpty(), "successful round-trip emits no warnings");
    }

    @Test
    @DisplayName("decoder returns empty for null carrier value")
    void decoderNullCarrierReturnsEmpty() {
        ContextDecodeResult<CorrelationContext> result = decoder.decode(null, DECODE_CTX);
        assertFalse(result.value().isPresent());
        assertTrue(result.warnings().isEmpty(), "null carrier is not a failure — it's just absent");
    }

    @Test
    @DisplayName("decoder returns failure with a warning when the carrier is the wrong type")
    void decoderWrongTypeReturnsFailure() {
        ContextDecodeResult<CorrelationContext> result = decoder.decode("not-a-snapshot", DECODE_CTX);
        assertFalse(result.value().isPresent());
        assertFalse(result.warnings().isEmpty(), "wrong type must emit at least one warning");
        // Warning describes the unexpected class so operators can debug serializer drift.
        assertTrue(result.warnings().get(0).reason().contains(String.class.getName()));
    }

    @Test
    @DisplayName("encoder + decoder round-trip preserves the full snapshot equality")
    void encoderDecoderRoundTrip() {
        MutableCorrelationContext live = new MutableCorrelationContext(REQ_ID, CORR_ID);
        live.putAttribute("k", "v");

        Object carrier = encoder.encode(live, ENCODE_CTX);
        ContextDecodeResult<CorrelationContext> decoded = decoder.decode(carrier, DECODE_CTX);

        assertTrue(decoded.value().isPresent());
        CorrelationContext rebuilt = decoded.value().get();
        assertNotSame(live, rebuilt);
        assertEquals(live.snapshot(), rebuilt.snapshot());
    }

    @Test
    @DisplayName("encoder key matches CorrelationContext.class.getName() so receivers can map it back")
    void encoderKey() {
        assertNotNull(encoder.key());
        assertEquals(CorrelationContext.class.getName(), encoder.key());
        assertEquals(CorrelationContext.class.getName(), decoder.key());
    }
}
