// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SystemIdentities;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for durable carriage of {@link IdentitySnapshot} over the existing durable-context seam
 * (PRD-ID-002 §14.3 "Durable carriage", §14.6 amendment A9 F1/F6 — re-encode of a receive-side
 * context and the capturedAt-anchored envelope expiry).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IdentitySnapshotCarriageTest {

    private static final String NAMESPACE = "identity-snapshot";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));
    private static final DelegationSummary DELEGATION = new DelegationSummary("on-behalf-of", Optional.of("grant-7"));
    private static final ClientRef CLIENT = new ClientRef("client-abc", "jwt-azp", Map.of("app", "mobile"));
    private static final List<AuthorityClaim> CLAIMS = List.of(
            new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of()),
            new AuthorityClaim(AuthorityKind.SCOPE, "payments:write", "idp", "aud", "jwt-scope", Map.of()));

    @Test
    @DisplayName("encoder writes signed snapshot metadata that the decoder round-trips back into an equal snapshot")
    void captureToMetadataAndBindBack() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        IdentitySnapshotContent content = capturedContent();
        IdentitySnapshotContext context = IdentitySnapshotContext.of(content);

        // A real per-row DurableCarrierDescriptor, matching how a wired boundary (e.g. delayed-job)
        // threads a carrier: the sentinel-signed shape is a separate, fail-closed case covered by
        // sentinelSignedSnapshotBindsUnverifiable (F3a) — this test proves the verified content-fidelity
        // round trip for a boundary that IS bound to a real carrier.
        DurableCarrierDescriptor carrier =
                new DurableCarrierDescriptor("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));
        DurableMetadata metadata = encoder.encode(context, new DurableEncodeContext("outbox", Optional.of(carrier)));

        assertEquals(NAMESPACE, encoder.namespace());
        assertEquals(NAMESPACE, decoder.namespace());
        assertTrue(metadata.has(NAMESPACE), "encoded metadata must carry the identity-snapshot namespace");
        assertTrue(
                metadata.body(NAMESPACE).map(b -> b.getBoolean("present")).orElse(false),
                "encoded metadata must carry the identity-snapshot.present companion flag");

        ContextDecodeResult<IdentitySnapshotContext> result =
                decoder.decode(metadata, new DurableDecodeContext("outbox", Optional.of(carrier)));

        assertTrue(result.value().isPresent(), "decode of untampered metadata must succeed");
        assertTrue(result.warnings().isEmpty(), "a successful decode carries no warnings");
        assertTrue(result.value().orElseThrow().verified(), "a verified decode binds a verified context");
        IdentitySnapshot decoded = result.value().orElseThrow().snapshot().orElseThrow();

        assertEquals(2, decoded.schemaVersion());
        // The full captured identity dimension survives the encode -> sign -> decode -> verify round trip.
        assertEquals(content, decoded.content());
        // The real per-row carrier supplied at encode time is signed and matched at decode.
        assertEquals("carrier-1", decoded.carrier().carrierId());
        assertEquals("outbox", decoded.carrier().target().kind());
        assertEquals("key-1", decoded.integrity().keyId());
    }

    @Test
    @DisplayName("decoder binds a present-but-unverifiable context (not a verified value) for tampered metadata")
    void decoderVerifiesHmac() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        IdentitySnapshotContext context = IdentitySnapshotContext.of(capturedContent());
        DurableMetadata valid = encoder.encode(context, new DurableEncodeContext("outbox"));

        DurableMetadata tampered = tamperEncodedSnapshot(valid);

        ContextDecodeResult<IdentitySnapshotContext> result =
                decoder.decode(tampered, new DurableDecodeContext("outbox"));

        // A present-but-unverifiable snapshot must bind an unverifiable context (routing it to the
        // receive-side degradation gate), never a verified value and never a silently-dropped value.
        assertTrue(result.value().isPresent(), "a tampered snapshot must bind a present-but-unverifiable context");
        assertFalse(result.value().orElseThrow().verified(), "the bound context must not be verified");
        assertTrue(
                result.value().orElseThrow().unverifiableReason().isPresent(),
                "the bound context must carry a typed degradation reason");
        assertTrue(result.value().orElseThrow().snapshot().isEmpty(), "an unverifiable context retains no snapshot");
        assertFalse(result.warnings().isEmpty(), "an unverifiable decode still carries a diagnostic warning");
    }

    @Test
    @DisplayName("F3a: a snapshot signed with the pre-boundary-wiring 'unbound' sentinel carrier binds an unverifiable"
            + " context, never a reconstructable verified one")
    void sentinelSignedSnapshotBindsUnverifiable() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        IdentitySnapshotContext context = IdentitySnapshotContext.of(capturedContent());
        // Carrier-less encode overload: no DurableCarrierDescriptor is supplied, so the encoder signs
        // the fixed "unbound" sentinel — exactly the shape an unwired boundary (outbox today,
        // kafka/workflow-branch until wired) produces. Decoding with the matching carrier-less
        // context means the decoder's expected carrier is ALSO the sentinel — the precise scenario
        // where the F5 carrierMatches check alone is a no-op (both sides equal "unbound").
        DurableMetadata metadata = encoder.encode(context, new DurableEncodeContext("outbox"));

        ContextDecodeResult<IdentitySnapshotContext> result =
                decoder.decode(metadata, new DurableDecodeContext("outbox"));

        assertTrue(result.value().isPresent(), "a sentinel-signed snapshot must still bind a present value");
        IdentitySnapshotContext bound = result.value().orElseThrow();
        assertFalse(
                bound.verified(),
                "a sentinel-signed snapshot must never bind a verified context — a sentinel carries no real"
                        + " per-row binding, so it must not be reconstructable");
        assertTrue(
                bound.unverifiableReason().isPresent(),
                "a sentinel-signed snapshot must bind a typed degradation reason so the receive-side gate fires");
        assertTrue(bound.snapshot().isEmpty(), "an unverifiable context must retain no reconstructable snapshot");
    }

    @Test
    @DisplayName("a snapshot signed with a real per-row carrier still verifies when the expected carrier matches")
    void realCarrierSignedSnapshotStillVerifies() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                "job-123", new DurableTarget("delayed-job", "handler-address", Optional.empty()));
        IdentitySnapshotContext context = IdentitySnapshotContext.of(capturedContent());
        DurableMetadata metadata =
                encoder.encode(context, new DurableEncodeContext("delayed-job", Optional.of(carrier)));

        ContextDecodeResult<IdentitySnapshotContext> result =
                decoder.decode(metadata, new DurableDecodeContext("delayed-job", Optional.of(carrier)));

        assertTrue(result.value().isPresent(), "decode of a real-carrier-signed snapshot must succeed");
        assertTrue(
                result.value().orElseThrow().verified(),
                "a real per-row carrier that matches the expected carrier must still bind a verified context —"
                        + " the F3a sentinel check must not over-broaden to real carriers");
        assertTrue(result.warnings().isEmpty(), "a successful decode carries no warnings");
    }

    @Test
    @DisplayName("a real signed carrier that does not match the expected carrier still binds an unverifiable context")
    void realCarrierMismatchStillUnverifiable() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        DurableCarrierDescriptor signedCarrier = new DurableCarrierDescriptor(
                "job-123", new DurableTarget("delayed-job", "handler-address", Optional.empty()));
        DurableCarrierDescriptor expectedCarrier = new DurableCarrierDescriptor(
                "job-456", new DurableTarget("delayed-job", "handler-address", Optional.empty()));
        IdentitySnapshotContext context = IdentitySnapshotContext.of(capturedContent());
        DurableMetadata metadata =
                encoder.encode(context, new DurableEncodeContext("delayed-job", Optional.of(signedCarrier)));

        ContextDecodeResult<IdentitySnapshotContext> result =
                decoder.decode(metadata, new DurableDecodeContext("delayed-job", Optional.of(expectedCarrier)));

        assertTrue(result.value().isPresent(), "a carrier mismatch must still bind a present-but-unverifiable context");
        assertFalse(result.value().orElseThrow().verified(), "a carrier mismatch must never bind a verified context");
        assertTrue(
                result.value().orElseThrow().unverifiableReason().isPresent(),
                "a carrier mismatch must bind a typed degradation reason");
        assertFalse(result.warnings().isEmpty(), "an unverifiable decode still carries a diagnostic warning");
    }

    // --- F1: re-encode of a receive-side context (P2.S0 security review) ---

    @Test
    @DisplayName("F1: re-encoding a verified receive-side context for a new carrier re-signs the same content, "
            + "preserving capturedAt, rather than throwing")
    void reEncodeOfVerifiedContextSucceedsForNewCarrier() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        IdentitySnapshotContent content = capturedContent();
        DurableCarrierDescriptor carrier1 = new DurableCarrierDescriptor(
                "carrier-1", new DurableTarget("workflow-timer", "wf-1", Optional.empty()));
        DurableMetadata firstEnvelope = encoder.encode(
                IdentitySnapshotContext.of(content), new DurableEncodeContext("delayed-job", Optional.of(carrier1)));
        ContextDecodeResult<IdentitySnapshotContext> firstDecode =
                decoder.decode(firstEnvelope, new DurableDecodeContext("delayed-job", Optional.of(carrier1)));
        IdentitySnapshotContext verifiedContext = firstDecode.value().orElseThrow();
        assertTrue(verifiedContext.verified(), "setup: the original envelope must decode to a verified context");

        // Re-encode the RECEIVE-SIDE verified context — not the original producer-side content
        // wrapper — for a DIFFERENT carrier: the exact shape a workflow-timer orphan re-enqueue
        // produces when it binds a decoded snapshot and then performs an ordinary enqueue.
        DurableCarrierDescriptor carrier2 = new DurableCarrierDescriptor(
                "carrier-2", new DurableTarget("delayed-job", "handler-address", Optional.empty()));
        DurableMetadata reEncoded =
                encoder.encode(verifiedContext, new DurableEncodeContext("delayed-job", Optional.of(carrier2)));

        assertTrue(
                reEncoded.has(NAMESPACE),
                "re-encoding a verified context must still produce the identity-snapshot namespace");

        ContextDecodeResult<IdentitySnapshotContext> secondDecode =
                decoder.decode(reEncoded, new DurableDecodeContext("delayed-job", Optional.of(carrier2)));
        assertTrue(
                secondDecode.value().isPresent(),
                "the re-encoded envelope must decode successfully against the new carrier");
        IdentitySnapshotContext reVerified = secondDecode.value().orElseThrow();
        assertTrue(reVerified.verified(), "the re-encoded envelope must verify");
        IdentitySnapshot reSigned = reVerified.snapshot().orElseThrow();

        assertEquals(content, reSigned.content(), "re-encode must sign the SAME captured content");
        assertEquals(
                content.capturedAt(),
                reSigned.content().capturedAt(),
                "capturedAt must be preserved unchanged across re-encode (anti-renewal freshness anchor)");
        assertEquals(
                "carrier-2", reSigned.carrier().carrierId(), "the re-encoded envelope must bind to the NEW carrier");
    }

    @Test
    @DisplayName("F6: the encoder anchors the signed expiresAt on capturedAt, not on the later issuedAt — "
            + "content captured well before encode time gets a capturedAt-anchored expiry")
    void encodeAnchorsExpiryOnCapturedAtNotIssuedAt() {
        // issuedAt (the encoder's clock at encode time) is deliberately hours AFTER capturedAt, so an
        // issuedAt-anchored expiry (the pre-F6 bug) and a capturedAt-anchored expiry (F6) are
        // observably DIFFERENT values — a test where the two are close together (as in
        // #reEncodeOfVerifiedContextSucceedsForNewCarrier's fixture) cannot distinguish them.
        Instant capturedAt = Instant.parse("2026-07-01T10:15:31Z");
        Instant encodeTime = capturedAt.plus(Duration.ofHours(3));
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(
                hmac,
                new SnapshotFreshnessPolicy(
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        Clock.fixed(encodeTime, ZoneOffset.UTC)));
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder decoder = new IdentitySnapshotDurableDecoder(codec);

        IdentitySnapshotContent content = new IdentitySnapshotContent(
                ACTOR,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                capturedAt,
                Optional.empty(),
                List.of(),
                "rest:authenticated",
                capturedAt);
        DurableCarrierDescriptor carrier =
                new DurableCarrierDescriptor("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));
        DurableMetadata metadata = encoder.encode(
                IdentitySnapshotContext.of(content), new DurableEncodeContext("outbox", Optional.of(carrier)));

        ContextDecodeResult<IdentitySnapshotContext> decoded =
                decoder.decode(metadata, new DurableDecodeContext("outbox", Optional.of(carrier)));
        assertTrue(decoded.value().isPresent(), "setup: the freshly-encoded envelope must decode and verify");
        IdentitySnapshot signed = decoded.value().orElseThrow().snapshot().orElseThrow();

        assertEquals(encodeTime, signed.issuedAt(), "setup sanity: issuedAt must equal the encoder's clock");
        assertEquals(
                capturedAt.plus(Duration.ofHours(24)),
                signed.expiresAt(),
                "expiresAt must be anchored on capturedAt (F6) — an issuedAt-anchored expiry would be "
                        + "capturedAt + 3h + 24h instead");
    }

    @Test
    @DisplayName("F1: re-encoding a present-but-unverifiable receive-side context emits no identity-snapshot "
            + "namespace and never throws")
    void reEncodeOfUnverifiableContextEncodesToEmptyMetadata() {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);

        IdentitySnapshotContext unverifiable =
                IdentitySnapshotContext.unverifiable(SnapshotDegradationReason.DECODE_FAILED);

        DurableMetadata encoded = encoder.encode(unverifiable, new DurableEncodeContext("delayed-job"));

        assertTrue(
                encoded.isEmpty(),
                "an unverifiable context must encode to an empty document — nothing legitimate to sign");
        assertFalse(
                encoded.has(NAMESPACE),
                "an unverifiable context's re-encode must not emit the identity-snapshot namespace");
    }

    @Test
    @DisplayName("F1: mergeCaptured over a bound unverifiable IdentitySnapshotContext skips the identity-snapshot "
            + "namespace rather than throwing (DurableContextPropagator/captureOne 'nothing to encode' contract)")
    void reEncodeOfUnverifiableContextEmitsNoSnapshotViaMergeCaptured(Vertx vertx, VertxTestContext ctx) {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        IdentitySnapshotCodec codec = newCodec(hmac);
        IdentitySnapshotDurableEncoder encoder = new IdentitySnapshotDurableEncoder(codec);

        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(encoder), Set.of());
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope scope = holder.bind(
                    IdentitySnapshotContext.class,
                    IdentitySnapshotContext.unverifiable(SnapshotDegradationReason.DECODE_FAILED))) {
                DurableMetadata merged = propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job");
                assertFalse(
                        merged.has(NAMESPACE),
                        "re-encoding an unverifiable context via mergeCaptured must not emit the identity-snapshot"
                                + " namespace, and must not throw");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("captureFrom is a no-op when the global kill-switch disables it")
    void captureDisabledByKillSwitch() {
        FakeContextHolder holder = new FakeContextHolder();
        IdentitySnapshotCapture capture =
                new IdentitySnapshotCapture(holder, new DefaultIdentitySnapshotFactory(holder), false);
        SecurityContext live = SecurityContexts.system(SystemIdentities.scheduledJob("carriage-test"));

        ContextHolder.Scope scope = capture.captureFrom(live);

        assertSame(ContextScopes.noop(), scope, "capture must return the shared no-op scope when captureEnabled=false");
        assertTrue(
                holder.current(IdentitySnapshotContext.class).isEmpty(),
                "no IdentitySnapshotContext should be bound when the kill-switch is off");
    }

    /**
     * A fixed instant shortly after {@link #capturedContent()}'s {@code capturedAt}, used as every
     * test codec's clock so encode/decode in this class is deterministic regardless of the real
     * wall-clock date. Since F6 anchors the codec's default envelope TTL (24h) on the immutable
     * {@code capturedAt} rather than {@code issuedAt}, a codec built with {@link Clock#systemUTC()}
     * would make a snapshot captured at the fixed {@code 2026-07-01} constant look stale — and
     * freshness checks would fail closed with {@code MALFORMED_TEMPORAL} — on any test run more than
     * 24h after that date.
     */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T10:16:00Z");

    /**
     * Builds a codec backed by {@code hmac} and a clock fixed at {@link #FIXED_NOW}, so decode-time
     * freshness checks see a stable "now" close to {@link #capturedContent()}'s {@code capturedAt}
     * regardless of when the test suite actually runs.
     *
     * @param hmac the HMAC signer/verifier the codec signs and verifies with
     * @return a codec with a deterministic clock
     */
    private static IdentitySnapshotCodec newCodec(SnapshotHmac hmac) {
        return new IdentitySnapshotCodec(
                hmac,
                new SnapshotFreshnessPolicy(
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        Clock.fixed(FIXED_NOW, ZoneOffset.UTC)));
    }

    /**
     * Builds the captured {@link IdentitySnapshotContent} the producer-side capture binds; the encoder
     * assembles the durable envelope (carrier + temporal bounds) around it and signs it.
     *
     * @return captured content ready to be bound and encoded
     */
    private static IdentitySnapshotContent capturedContent() {
        return new IdentitySnapshotContent(
                ACTOR,
                Optional.of(SUBJECT),
                Optional.of(DELEGATION),
                Optional.of(CLIENT),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                CLAIMS,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
    }

    /**
     * Corrupts the base64-encoded codec bytes carried in the {@code identity-snapshot} namespace
     * body of {@code metadata} so the codec's HMAC verification fails on decode, while leaving the
     * document otherwise well-formed (still base64, still valid JSON once decoded).
     *
     * @param metadata the validly encoded metadata to tamper with
     * @return a new {@link DurableMetadata} carrying the tampered body
     */
    private static DurableMetadata tamperEncodedSnapshot(DurableMetadata metadata) {
        JsonObject body = metadata.body(NAMESPACE).orElseThrow();
        String base64 = body.getString("snapshot");
        byte[] raw = java.util.Base64.getDecoder().decode(base64);
        raw[raw.length - 1] ^= 0x01;
        String tamperedBase64 = java.util.Base64.getEncoder().encodeToString(raw);
        JsonObject tamperedBody = body.copy().put("snapshot", tamperedBase64);
        return DurableMetadata.of(NAMESPACE, tamperedBody);
    }

    /**
     * Minimal in-memory {@link ContextHolder} test double sufficient to observe whether
     * {@link IdentitySnapshotCapture} decided to bind a value, without depending on the real
     * Vert.x-context-backed implementation in {@code vertique-context}.
     */
    private static final class FakeContextHolder implements ContextHolder {

        private final Map<Class<?>, Object> bindings = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> current(Class<T> type) {
            return Optional.ofNullable((T) bindings.get(type));
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            Object previous = bindings.put(type, value);
            return () -> {
                if (previous == null) {
                    bindings.remove(type);
                } else {
                    bindings.put(type, previous);
                }
            };
        }
    }
}
