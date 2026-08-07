// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IdentitySnapshotCodec} against the schema-v2 content/envelope split (PRD-ID-002
 * §14.6 amendment A9): a signed {@link IdentitySnapshot} carries an {@link IdentitySnapshotContent}
 * plus a {@link SnapshotCarrierBinding}, {@code issuedAt}/{@code expiresAt}, and the integrity
 * envelope. The codec signs the whole v2 envelope, and {@code decode} verifies the HMAC before
 * enforcing freshness.
 */
class IdentitySnapshotCodecTest {

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme"));
    private static final PrincipalRef SUBJECT =
            new PrincipalRef(PrincipalType.USER, "user-42", Map.of("realm", "acme-realm"));
    private static final DelegationSummary DELEGATION = new DelegationSummary("on-behalf-of", Optional.of("grant-7"));
    private static final ClientRef CLIENT = new ClientRef("client-abc", "jwt-azp", Map.of("app", "mobile"));
    private static final List<AuthorityClaim> CLAIMS = List.of(
            new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of()),
            new AuthorityClaim(AuthorityKind.SCOPE, "payments:write", "idp", "aud", "jwt-scope", Map.of()));
    private static final SnapshotCarrierBinding CARRIER =
            new SnapshotCarrierBinding("carrier-1", new DurableTarget("outbox", "orders", Optional.empty()));

    private static IdentitySnapshotContent content(
            PrincipalRef actor,
            Optional<PrincipalRef> subject,
            Optional<DelegationSummary> delegation,
            Optional<ClientRef> client,
            List<AuthorityClaim> claims) {
        return new IdentitySnapshotContent(
                actor,
                subject,
                delegation,
                client,
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                claims,
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
    }

    private static IdentitySnapshot unsigned(IdentitySnapshotContent content) {
        Instant issuedAt = Instant.now();
        return new IdentitySnapshot(
                2,
                content,
                CARRIER,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));
    }

    private static IdentitySnapshotCodec newCodec() {
        return new IdentitySnapshotCodec(
                new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1"));
    }

    @Test
    @DisplayName("the v2 envelope — content, carrier, issuedAt, expiresAt — survives encode/decode")
    void v2EnvelopeRoundTrips() {
        IdentitySnapshotCodec codec = newCodec();

        IdentitySnapshotContent content =
                content(ACTOR, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.of(CLIENT), CLAIMS);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(3600);
        IdentitySnapshot unsigned = new IdentitySnapshot(
                2, content, CARRIER, issuedAt, expiresAt, new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));

        byte[] encoded = codec.encode(unsigned);
        IdentitySnapshot decoded = codec.decode(encoded);

        assertEquals(2, decoded.schemaVersion());
        assertEquals(content, decoded.content());
        assertEquals(CARRIER, decoded.carrier());
        assertEquals(issuedAt, decoded.issuedAt());
        assertEquals(expiresAt, decoded.expiresAt());
        assertEquals("key-1", decoded.integrity().keyId());
    }

    @Test
    @DisplayName("IdentitySnapshot is credential-free by construction — no evidence/token component")
    void credentialFreeByConstruction() {
        for (RecordComponent component : IdentitySnapshot.class.getRecordComponents()) {
            String name = component.getName().toLowerCase();
            String typeName = component.getType().getName().toLowerCase();
            assertTrue(
                    !name.contains("evidence")
                            && !name.contains("token")
                            && !typeName.contains("authenticationevidence")
                            && !typeName.contains("tokenattributes"),
                    "IdentitySnapshot must not carry a credential-bearing component, found: " + component.getName());
        }
    }

    @Test
    @DisplayName("F6: envelopeExpiry anchors the default TTL on capturedAt, not issuedAt, so a later issuedAt "
            + "at the same capturedAt does not change the expiry")
    void envelopeExpiryAnchoredOnCapturedAtByDefault() {
        IdentitySnapshotCodec codec = newCodec();
        Instant capturedAt = Instant.parse("2026-07-01T10:15:31Z");

        Instant expiresAt = codec.envelopeExpiry(capturedAt);

        assertEquals(
                capturedAt.plus(Duration.ofHours(24)),
                expiresAt,
                "the documented default envelope TTL is capturedAt + 24h, not issuedAt + 24h");

        // A chained re-encode mints a fresh issuedAt but must reuse the SAME immutable capturedAt —
        // envelopeExpiry no longer even accepts issuedAt as a parameter, so calling it again with the
        // same capturedAt must yield the identical expiresAt regardless of how much wall-clock time
        // has passed between the original capture and the re-encode.
        Instant reEncodedExpiresAt = codec.envelopeExpiry(capturedAt);
        assertEquals(
                expiresAt,
                reEncodedExpiresAt,
                "re-encoding with the same capturedAt must yield the same expiresAt — a chained re-encode "
                        + "must not renew the signed expiry window");
    }

    @Test
    @DisplayName("decode rejects a schemaVersion above the supported v2")
    void rejectsSchemaVersionAboveTwo() {
        IdentitySnapshotCodec codec = newCodec();

        IdentitySnapshot known =
                unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
        byte[] encodedAtKnownVersion = codec.encode(known);
        byte[] bumpedToUnknownVersion = bumpSchemaVersion(encodedAtKnownVersion, 3);

        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> codec.decode(bumpedToUnknownVersion));
        assertEquals(
                SnapshotDegradationReason.SCHEMA_INCOMPATIBLE,
                failure.reason(),
                "a schemaVersion above 2 must surface reason SCHEMA_INCOMPATIBLE");
    }

    @Test
    @DisplayName("decode rejects a tampered integrity tag with reason BAD_HMAC")
    void decodeRejectsTamperedTagWithReason() {
        IdentitySnapshotCodec codec = newCodec();

        byte[] encoded =
                codec.encode(unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of())));
        String json = new String(encoded, StandardCharsets.UTF_8);
        String tampered = json.replaceFirst("\"tag\"\\s*:\\s*\"[^\"]+\"", "\"tag\":\"tampered-tag-value\"");

        IdentitySnapshotCodecException failure = assertThrows(
                IdentitySnapshotCodecException.class, () -> codec.decode(tampered.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                SnapshotDegradationReason.BAD_HMAC,
                failure.reason(),
                "a tampered integrity tag must surface reason BAD_HMAC");
    }

    @Test
    @DisplayName("decode rejects an integrity envelope naming an unknown keyId with reason UNKNOWN_KEY")
    void decodeRejectsUnknownKeyIdWithReason() {
        IdentitySnapshotCodec codec = newCodec();

        byte[] encoded =
                codec.encode(unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of())));
        String json = new String(encoded, StandardCharsets.UTF_8);
        String rewritten = json.replaceFirst("\"keyId\"\\s*:\\s*\"[^\"]+\"", "\"keyId\":\"no-such-key\"");

        IdentitySnapshotCodecException failure = assertThrows(
                IdentitySnapshotCodecException.class, () -> codec.decode(rewritten.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                SnapshotDegradationReason.UNKNOWN_KEY,
                failure.reason(),
                "an integrity envelope naming an unconfigured keyId must surface reason UNKNOWN_KEY");
    }

    @Test
    @DisplayName("decode rejects verification against an empty keyset with reason KEY_UNAVAILABLE")
    void decodeRejectsEmptyKeysetWithReason() {
        IdentitySnapshotCodec signingCodec = newCodec();
        byte[] encoded = signingCodec.encode(
                unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of())));

        // Verifying codec has no keys configured at all — the keyset lookup itself is empty,
        // distinct from a populated keyset simply missing this particular keyId.
        IdentitySnapshotCodec verifyingCodec = new IdentitySnapshotCodec(new SnapshotHmac(Map.of(), "key-1"));

        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> verifyingCodec.decode(encoded));
        assertEquals(
                SnapshotDegradationReason.KEY_UNAVAILABLE,
                failure.reason(),
                "verification against an empty keyset must surface reason KEY_UNAVAILABLE");
    }

    @Test
    @DisplayName("decode rejects a non-object JSON payload (e.g. a JSON array)")
    void decodeRejectsNonObjectJson() {
        IdentitySnapshotCodec codec = newCodec();

        byte[] arrayJson = "[1,2]".getBytes(StandardCharsets.UTF_8);

        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> codec.decode(arrayJson));
        assertEquals(
                SnapshotDegradationReason.DECODE_FAILED,
                failure.reason(),
                "a non-object JSON payload (a JSON array) must surface reason DECODE_FAILED");
    }

    @Test
    @DisplayName("decode rejects a payload missing the schemaVersion field")
    void decodeRejectsMissingSchemaVersion() {
        IdentitySnapshotCodec codec = newCodec();

        byte[] encoded =
                codec.encode(unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of())));
        String json = new String(encoded, StandardCharsets.UTF_8);
        // schemaVersion is checked before HMAC verification (see IdentitySnapshotCodec#decode), so
        // stripping it does not require re-signing the payload.
        String rewritten = json.replaceFirst("\"schemaVersion\"\\s*:\\s*\\d+,?", "");

        IdentitySnapshotCodecException failure = assertThrows(
                IdentitySnapshotCodecException.class, () -> codec.decode(rewritten.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                SnapshotDegradationReason.DECODE_FAILED,
                failure.reason(),
                "a payload missing schemaVersion must surface reason DECODE_FAILED");
    }

    @Test
    @DisplayName("decode rejects a payload missing the integrity envelope")
    void decodeRejectsMalformedIntegrityEnvelope() {
        IdentitySnapshotCodec codec = newCodec();

        byte[] encoded =
                codec.encode(unsigned(content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of())));
        String json = new String(encoded, StandardCharsets.UTF_8);
        // schemaVersion passes (integrity is checked afterward), so this exercises verifyTree's
        // integrityNode() fail-closed guard rather than the earlier schemaVersion check.
        String rewritten = json.replaceFirst("\"integrity\"\\s*:\\s*\\{[^}]*\\},?", "");

        IdentitySnapshotCodecException failure = assertThrows(
                IdentitySnapshotCodecException.class, () -> codec.decode(rewritten.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                SnapshotDegradationReason.DECODE_FAILED,
                failure.reason(),
                "a payload missing the integrity envelope must surface reason DECODE_FAILED");
    }

    @Test
    @DisplayName("verifyIntegrity rejects an envelope declaring an algorithm outside the allowlist, fail-closed")
    void verifyIntegrityRejectsDisallowedAlgorithm() {
        IdentitySnapshotCodec codec = newCodec();

        // The blob-declared algorithm originates from the app-writable durable store; the verifier
        // must not accept it as its own primitive. An out-of-allowlist algorithm ("NoSuchAlg") must
        // be rejected before Mac.getInstance and surface a typed fail-closed degradation reason.
        Instant issuedAt = Instant.now();
        IdentitySnapshot forged = new IdentitySnapshot(
                2,
                content(ACTOR, Optional.empty(), Optional.empty(), Optional.empty(), List.of()),
                CARRIER,
                issuedAt,
                issuedAt.plusSeconds(3600),
                new SnapshotIntegrity("NoSuchAlg", "key-1", "forged-tag-value"));

        IdentitySnapshotCodecException failure =
                assertThrows(IdentitySnapshotCodecException.class, () -> codec.verifyIntegrity(forged));
        assertEquals(
                SnapshotDegradationReason.BAD_HMAC,
                failure.reason(),
                "an out-of-allowlist algorithm must surface reason BAD_HMAC, fail-closed");
    }

    @Test
    @DisplayName("HMAC canonicalization is independent of attribute-map iteration order")
    void verifyIsOrderIndependentAcrossMapIteration() throws Exception {
        // The canonical mapper must emit every Map<String,Object> attribute map (nested under the
        // snapshot's content) in a deterministic, key-sorted order, so a snapshot signed on one node
        // verifies on another (different startup SALT). This pins the mapper-level invariant directly.
        IdentitySnapshotCodec codec = newCodec();
        ObjectMapper canonicalMapper = canonicalMapperOf(codec);

        LinkedHashMap<String, Object> forwardOrder = new LinkedHashMap<>();
        forwardOrder.put("alpha", "1");
        forwardOrder.put("beta", "2");
        forwardOrder.put("gamma", "3");

        LinkedHashMap<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("gamma", "3");
        reverseOrder.put("beta", "2");
        reverseOrder.put("alpha", "1");

        byte[] forwardBytes = canonicalMapper.writeValueAsBytes(forwardOrder);
        byte[] reverseBytes = canonicalMapper.writeValueAsBytes(reverseOrder);

        assertArrayEquals(
                forwardBytes,
                reverseBytes,
                "the codec's canonical mapper must serialize equal maps to identical bytes regardless "
                        + "of iteration order; otherwise cross-JVM HMAC verification is non-deterministic");
    }

    @Test
    @DisplayName("decode verifies a snapshot whose attribute maps each carry multiple keys")
    void decodeVerifiesSnapshotWithMultiKeyAttributeMaps() {
        IdentitySnapshotCodec codec = newCodec();

        PrincipalRef multiKeyActor = new PrincipalRef(
                PrincipalType.SERVICE, "svc-scheduler", Map.of("tenant", "acme", "region", "eu-west", "tier", "gold"));
        List<AuthorityClaim> multiKeyClaims = List.of(new AuthorityClaim(
                AuthorityKind.ROLE, "admin", "idp", "aud", "jwt-roles", Map.of("env", "prod", "scope", "full")));

        IdentitySnapshot unsigned = unsigned(content(
                multiKeyActor, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.of(CLIENT), multiKeyClaims));

        IdentitySnapshot decoded = codec.decode(codec.encode(unsigned));

        assertEquals(multiKeyActor, decoded.content().actor());
        assertEquals(multiKeyClaims, decoded.content().authorizationClaims());
    }

    @Test
    @DisplayName("decode verifies a snapshot whose numeric attribute values would drift across a JSON round trip")
    void verifyPreservesBigDecimalAttributeValues() {
        // The verifier canonicalizes over the parsed JSON tree on both sides, so a value's stored
        // representation is exactly what is MAC'd — BigDecimal("1.00") etc. do not fail their own tag.
        IdentitySnapshotCodec codec = newCodec();

        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("weight", new BigDecimal("2.50"));
        nested.put("count", 7);
        LinkedHashMap<String, Object> actorAttributes = new LinkedHashMap<>();
        actorAttributes.put("price", new BigDecimal("1.00"));
        actorAttributes.put("smallInt", 42);
        actorAttributes.put("bigLong", 9_999_999_999L);
        actorAttributes.put("ratio", 1.5d);
        actorAttributes.put("nested", nested);
        PrincipalRef numericActor = new PrincipalRef(PrincipalType.SERVICE, "svc-billing", actorAttributes);

        LinkedHashMap<String, Object> claimAttributes = new LinkedHashMap<>();
        claimAttributes.put("limit", new BigDecimal("10.00"));
        claimAttributes.put("scaleFactor", 3.0d);
        List<AuthorityClaim> numericClaims = List.of(
                new AuthorityClaim(AuthorityKind.SCOPE, "billing:write", "idp", "aud", "jwt-scope", claimAttributes));

        IdentitySnapshot unsigned = unsigned(content(
                numericActor, Optional.of(SUBJECT), Optional.of(DELEGATION), Optional.of(CLIENT), numericClaims));

        // Must NOT throw "identity snapshot integrity tag is invalid": the snapshot verifies its own
        // authoritative tag despite carrying value types that are lossy under Jackson's Object binding.
        IdentitySnapshot decoded = codec.decode(codec.encode(unsigned));

        assertEquals("svc-billing", decoded.content().actor().id());
        assertEquals(1, decoded.content().authorizationClaims().size());
        assertEquals("key-1", decoded.integrity().keyId());
    }

    // --- issue #181: a multi-element amr must survive a cross-process decode + verifyForUse ---

    /**
     * FROZEN pre-signed snapshot whose stored {@code content.assurance.amr} array is
     * {@code ["pwd","otp"]}, signed under {@code key-1}. Generated once by a throwaway harness and
     * hard-coded here so it stands in for a durable row written by a <em>different</em> process — a
     * snapshot the codec must accept without re-deriving the array order from its own typed model.
     */
    private static final String AMR_PWD_OTP_SNAPSHOT_JSON = """
            {"carrier":{"carrierId":"carrier-1","target":{"address":"orders","kind":"outbox","messageType":null}},"content":{"actor":{"attributes":{"tenant":"acme"},"id":"svc-scheduler","type":"SERVICE"},"assurance":{"acr":"urn:acr","amr":["pwd","otp"],"authTime":"2026-07-01T10:15:30Z","providerLevel":2},"authenticatedAt":"2026-07-01T10:15:30Z","authenticationMethodKind":"jwt","authorizationClaims":[{"attributes":{},"audience":"aud","issuer":"idp","kind":"ROLE","source":"jwt-roles","value":"admin"}],"capturedAt":"2026-07-01T10:15:31Z","client":{"attributes":{"app":"mobile"},"clientId":"client-abc","source":"jwt-azp"},"delegation":{"authorityId":"grant-7","kind":"on-behalf-of"},"originSummary":"rest:authenticated","subject":{"attributes":{"realm":"acme-realm"},"id":"user-42","type":"USER"}},"expiresAt":"2026-07-01T11:15:31Z","integrity":{"algorithm":"HmacSHA256","keyId":"key-1","tag":"VnjnocKofWb6n9yv_BbYgDSlywccHUwOfFE-FKsvxgo"},"issuedAt":"2026-07-01T10:15:31Z","schemaVersion":2}\
            """;

    /**
     * FROZEN pre-signed twin of {@link #AMR_PWD_OTP_SNAPSHOT_JSON} whose stored
     * {@code content.assurance.amr} array is the reversed {@code ["otp","pwd"]}, independently signed
     * under {@code key-1}. Together the pair covers both encounter orders, so exactly one of them
     * would happen to match any given JVM's hash-set iteration order — the asymmetry issue #181 is
     * about.
     */
    private static final String AMR_OTP_PWD_SNAPSHOT_JSON = """
            {"carrier":{"carrierId":"carrier-1","target":{"address":"orders","kind":"outbox","messageType":null}},"content":{"actor":{"attributes":{"tenant":"acme"},"id":"svc-scheduler","type":"SERVICE"},"assurance":{"acr":"urn:acr","amr":["otp","pwd"],"authTime":"2026-07-01T10:15:30Z","providerLevel":2},"authenticatedAt":"2026-07-01T10:15:30Z","authenticationMethodKind":"jwt","authorizationClaims":[{"attributes":{},"audience":"aud","issuer":"idp","kind":"ROLE","source":"jwt-roles","value":"admin"}],"capturedAt":"2026-07-01T10:15:31Z","client":{"attributes":{"app":"mobile"},"clientId":"client-abc","source":"jwt-azp"},"delegation":{"authorityId":"grant-7","kind":"on-behalf-of"},"originSummary":"rest:authenticated","subject":{"attributes":{"realm":"acme-realm"},"id":"user-42","type":"USER"}},"expiresAt":"2026-07-01T11:15:31Z","integrity":{"algorithm":"HmacSHA256","keyId":"key-1","tag":"mcqnEr0op2mReGIHhRNYxG0VDCnihYZefmicVqkwv3c"},"issuedAt":"2026-07-01T10:15:31Z","schemaVersion":2}\
            """;

    @Test
    @DisplayName(
            "a pre-signed snapshot storing amr as [\"pwd\",\"otp\"] decodes, keeps that order, and verifies for use")
    void preSignedPwdOtpAmrSnapshotVerifiesForUse() {
        assertPreSignedFixtureVerifiesForUse(AMR_PWD_OTP_SNAPSHOT_JSON, List.of("pwd", "otp"));
    }

    @Test
    @DisplayName(
            "a pre-signed snapshot storing amr as [\"otp\",\"pwd\"] decodes, keeps that order, and verifies for use")
    void preSignedOtpPwdAmrSnapshotVerifiesForUse() {
        assertPreSignedFixtureVerifiesForUse(AMR_OTP_PWD_SNAPSHOT_JSON, List.of("otp", "pwd"));
    }

    @Test
    @DisplayName("the two frozen amr fixtures really are distinct payloads carrying distinct integrity tags")
    void frozenAmrFixturesAreDistinctAndIndependentlySigned() {
        assertNotEquals(
                AMR_PWD_OTP_SNAPSHOT_JSON,
                AMR_OTP_PWD_SNAPSHOT_JSON,
                "the two fixtures must differ, otherwise they do not cover two encounter orders");
        assertNotEquals(
                tagOf(AMR_PWD_OTP_SNAPSHOT_JSON),
                tagOf(AMR_OTP_PWD_SNAPSHOT_JSON),
                "each fixture must carry its own signature over its own stored amr order");
    }

    @Test
    @DisplayName("no snapshot record component is declared as an unordered java.util.Set")
    void snapshotModelDeclaresNoUnorderedSetComponents() {
        // The HMAC is computed over a serialization of the typed model on the verifyIntegrity path,
        // so ANY unordered Set anywhere in the snapshot tree reintroduces the issue-#181 cross-process
        // verification failure. Pin the structural invariant, not just the one known offender.
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        queue.add(IdentitySnapshot.class);

        while (!queue.isEmpty()) {
            Class<?> record = queue.poll();
            if (!visited.add(record)) {
                continue;
            }
            for (RecordComponent component : record.getRecordComponents()) {
                Class<?> raw = component.getType();
                if (Set.class.isAssignableFrom(raw)) {
                    assertTrue(
                            SequencedSet.class.isAssignableFrom(raw),
                            record.getSimpleName() + "." + component.getName()
                                    + " is declared as an unordered " + raw.getName()
                                    + "; every set-valued snapshot component must be declared as a "
                                    + "java.util.SequencedSet so its stored JSON array order round-trips");
                }
                if (raw.isRecord()) {
                    queue.add(raw);
                }
                for (Class<?> argument : recordTypeArgumentsOf(component.getGenericType())) {
                    queue.add(argument);
                }
            }
        }
    }

    /**
     * Asserts that a frozen, externally-signed snapshot fixture decodes, exposes {@code amr} in the
     * exact order the fixture stores it, and passes {@link IdentitySnapshotCodec#verifyForUse} — the
     * three seams issue #181 breaks, asserted separately so a failure names which one gave way.
     *
     * @param fixtureJson      the frozen pre-signed snapshot JSON
     * @param expectedAmrOrder the amr order stored in {@code fixtureJson}
     */
    private static void assertPreSignedFixtureVerifiesForUse(String fixtureJson, List<String> expectedAmrOrder) {
        IdentitySnapshotCodec codec = fixtureCodec();
        byte[] stored = fixtureJson.getBytes(StandardCharsets.UTF_8);

        IdentitySnapshot decoded =
                assertDoesNotThrow(() -> codec.decode(stored), "the stored bytes must decode and verify as written");

        assertEquals(
                expectedAmrOrder,
                List.copyOf(decoded.content().assurance().orElseThrow().amr()),
                "the decoded typed model must preserve the stored amr array order");

        assertDoesNotThrow(
                () -> codec.verifyForUse(decoded),
                "verifyForUse re-serializes the typed model, so a snapshot whose stored amr order differs "
                        + "from the model's iteration order fails its own valid HMAC (issue #181)");
    }

    /**
     * Builds the codec used against the frozen fixtures: the same fixed keyset as {@link #newCodec()},
     * plus a clock pinned inside the fixtures' signed validity window so they verify forever.
     *
     * @return a codec whose freshness clock is fixed at {@code 2026-07-01T10:30:00Z}
     */
    private static IdentitySnapshotCodec fixtureCodec() {
        return new IdentitySnapshotCodec(
                new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1"),
                new SnapshotFreshnessPolicy(
                        Optional.empty(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        Clock.fixed(Instant.parse("2026-07-01T10:30:00Z"), ZoneOffset.UTC)));
    }

    /**
     * Extracts the {@code integrity.tag} value from a snapshot JSON string.
     *
     * @param json the snapshot JSON
     * @return the base64url tag value
     */
    private static String tagOf(String json) {
        int start = json.indexOf("\"tag\":\"") + "\"tag\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    /**
     * Returns the generic type arguments of {@code type} that are Vertique records — the container
     * elements ({@code Optional<T>}, {@code List<T>}, {@code Set<T>}, {@code Map<K,V>}) the snapshot
     * walker must descend into.
     *
     * @param type the component's generic type
     * @return the record type arguments, possibly empty
     */
    private static List<Class<?>> recordTypeArgumentsOf(Type type) {
        if (!(type instanceof ParameterizedType parameterized)) {
            return List.of();
        }
        List<Class<?>> records = new ArrayList<>();
        for (Type argument : parameterized.getActualTypeArguments()) {
            if (argument instanceof Class<?> raw
                    && raw.isRecord()
                    && raw.getName().startsWith("dev.vertique.")) {
                records.add(raw);
            }
        }
        return records;
    }

    /**
     * Reflectively reads the codec's single internal canonical {@link ObjectMapper} — the same mapper
     * the codec uses to produce the HMAC canonical bytes.
     *
     * @param codec the codec whose canonical mapper is being inspected
     * @return the codec's internal {@link ObjectMapper}
     * @throws ReflectiveOperationException if the {@code objectMapper} field cannot be accessed
     */
    private static ObjectMapper canonicalMapperOf(IdentitySnapshotCodec codec) throws ReflectiveOperationException {
        Field field = IdentitySnapshotCodec.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        return (ObjectMapper) field.get(codec);
    }

    /**
     * Rewrites the {@code schemaVersion} field of an encoded snapshot to {@code newVersion} by naive
     * textual substitution — good enough to construct an "unknown newer schema" fixture without
     * depending on codec internals.
     *
     * @param encoded    the originally encoded snapshot bytes
     * @param newVersion the schema version to substitute in
     * @return the rewritten bytes
     */
    private static byte[] bumpSchemaVersion(byte[] encoded, int newVersion) {
        String json = new String(encoded, StandardCharsets.UTF_8);
        String rewritten = json.replaceFirst("\"schemaVersion\"\\s*:\\s*\\d+", "\"schemaVersion\":" + newVersion);
        return rewritten.getBytes(StandardCharsets.UTF_8);
    }
}
