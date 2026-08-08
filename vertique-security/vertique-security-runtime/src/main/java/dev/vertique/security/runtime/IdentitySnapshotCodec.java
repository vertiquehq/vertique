// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.SnapshotIntegrity;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes and decodes {@link IdentitySnapshot} instances to and from a signed JSON byte
 * representation.
 *
 * <p>{@link #encode(IdentitySnapshot)} (re)computes the {@link IdentitySnapshot#integrity()}
 * envelope using the codec's configured {@link SnapshotHmac} active signing key before
 * serializing — the caller supplies the snapshot's business fields plus the intended
 * {@code algorithm}/{@code keyId}, and the codec produces the authoritative tag.
 * {@link #decode(byte[])} is the fail-closed counterpart: it rejects an unknown-newer
 * {@code schemaVersion} and verifies the snapshot's HMAC tag (against the active key or a
 * configured previous key, by the tag's own {@code keyId}) before returning it, throwing
 * {@link IdentitySnapshotCodecException} on any failure.
 *
 * <p>{@link #decode(byte[])} enforces both integrity <em>and</em> current freshness on every byte-level
 * decode. A caller that already holds a decoded, typed {@link IdentitySnapshot} — e.g. a reconstruction
 * entry point resuming a snapshot that was decoded earlier and retained in memory — must re-verify both
 * dimensions via {@link #verifyForUse(IdentitySnapshot)} rather than {@link #verifyIntegrity(IdentitySnapshot)}
 * alone: integrity verification alone cannot detect that the snapshot's effective expiry has since passed,
 * which would let a retained-but-stale snapshot be replayed into a live identity.
 */
public class IdentitySnapshotCodec {

    /**
     * The highest {@code schemaVersion} this codec knows how to decode. A snapshot encoded with a
     * higher version is rejected as unknown-newer rather than partially interpreted.
     */
    static final int SUPPORTED_SCHEMA_VERSION = 2;

    /**
     * Placeholder value substituted for {@code integrity.tag} while computing the canonical
     * payload that the real tag signs over, so the tag never signs over itself.
     */
    private static final String CANONICAL_TAG_PLACEHOLDER = "unsigned";

    /** JSON field name of the snapshot's integrity envelope. */
    private static final String INTEGRITY_FIELD = "integrity";

    /** JSON field name of the integrity envelope's algorithm. */
    private static final String ALGORITHM_FIELD = "algorithm";

    /** JSON field name of the integrity envelope's key id. */
    private static final String KEY_ID_FIELD = "keyId";

    /** JSON field name of the integrity envelope's MAC tag. */
    private static final String TAG_FIELD = "tag";

    /** JSON field name of the snapshot's schema version. */
    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";

    /** Default durable-envelope time-to-live applied by the encoder when no snapshot-lifetime budget is configured. */
    private static final Duration DEFAULT_ENVELOPE_TTL = Duration.ofHours(24);

    private final SnapshotHmac hmac;
    private final SnapshotFreshnessPolicy freshnessPolicy;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a codec that signs and verifies snapshots using the given {@link SnapshotHmac}
     * keyset and enforces the given {@link SnapshotFreshnessPolicy} at decode time.
     *
     * @param hmac            the HMAC signer/verifier used to sign an encoded snapshot's integrity
     *                        envelope and to validate a decoded snapshot's integrity envelope
     * @param freshnessPolicy the fail-closed freshness policy applied to a decoded snapshot's
     *                        temporal envelope after HMAC verification; also supplies the clock and
     *                        snapshot-lifetime budget the durable encoder reads to mint
     *                        {@code issuedAt}/{@code expiresAt}
     * @throws NullPointerException if {@code hmac} or {@code freshnessPolicy} is {@code null}
     */
    public IdentitySnapshotCodec(SnapshotHmac hmac, SnapshotFreshnessPolicy freshnessPolicy) {
        this.hmac = Objects.requireNonNull(hmac, "hmac");
        this.freshnessPolicy = Objects.requireNonNull(freshnessPolicy, "freshnessPolicy");
        this.objectMapper = buildCanonicalMapper();
    }

    /**
     * Convenience constructor for a codec with no freshness budgets configured: only a snapshot's
     * signed {@code expiresAt} bounds it, the tolerated clock skew is 30 seconds, and the clock is
     * {@link Clock#systemUTC()}. The production Dagger wiring uses the two-arg constructor with a
     * config-backed policy; this keeps unit fixtures that do not exercise budget tightening concise.
     *
     * @param hmac the HMAC signer/verifier; must not be {@code null}
     * @throws NullPointerException if {@code hmac} is {@code null}
     */
    public IdentitySnapshotCodec(SnapshotHmac hmac) {
        this(
                hmac,
                new SnapshotFreshnessPolicy(
                        Optional.empty(), Optional.empty(), Duration.ofSeconds(30), Clock.systemUTC()));
    }

    /**
     * Builds the canonical {@link ObjectMapper} used to produce the exact bytes a snapshot's HMAC
     * tag signs and verifies over. The configuration makes those bytes deterministic across JVMs,
     * nodes, and restarts so a snapshot signed on one node verifies on any other:
     *
     * <ul>
     *   <li>{@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} — the snapshot's {@code content}
     *       carries {@code Map<String,Object>} attribute maps ({@code PrincipalRef.attributes},
     *       {@code ClientRef.attributes}, and each {@code AuthorityClaim.attributes}) stored via
     *       {@code Map.copyOf(...)}, whose iteration order is randomized per JVM by a startup salt.
     *       Sorting map entries by key removes that per-JVM order from the canonical bytes.</li>
     *   <li>{@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} — pins object property order so it
     *       cannot depend on reflection ordering.</li>
     *   <li>{@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} — pins {@code BigDecimal} output
     *       to plain (non-scientific) notation.</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} disabled — serializes every
     *       {@link java.time.Instant} temporal field (the envelope's {@code issuedAt}/{@code expiresAt}
     *       and the content's {@code authenticatedAt}/{@code capturedAt}) as byte-stable ISO-8601
     *       strings rather than numeric timestamps.</li>
     * </ul>
     *
     * <p>This mirrors {@code FingerprintCanonicalizer.buildCanonicalMapper()} in the workflow
     * engine, adding the temporal pin required by the snapshot's {@code Instant} fields.
     *
     * @return a configured canonical {@link ObjectMapper}
     */
    private static ObjectMapper buildCanonicalMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Signs and serializes the given snapshot to its JSON byte representation.
     *
     * <p>The snapshot's {@link IdentitySnapshot#integrity()} tag is recomputed here using the
     * codec's active signing key and the snapshot's declared {@code algorithm} — any tag value the
     * caller supplied is replaced, since the codec is the sole source of an authoritative
     * signature.
     *
     * @param snapshot the snapshot to sign and encode
     * @return the UTF-8 JSON bytes representing the signed snapshot
     * @throws IdentitySnapshotCodecException if signing or serialization fails
     */
    public byte[] encode(IdentitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String algorithm = snapshot.integrity().algorithm();
        String keyId = hmac.activeKeyId();

        // Canonicalize over the parsed JSON tree, never over a re-serialized object model: the tree
        // fixes each attribute value's stored representation exactly as it will appear on the wire,
        // so verification (which only ever sees the stored bytes) recomputes byte-identical canonical
        // bytes. Signing over the model instead would drift for any Map<String,Object> value whose
        // Jackson JSON->Object binding is lossy (e.g. BigDecimal("1.00") -> Double 1.0).
        ObjectNode tree = canonicalTree(
                withIntegrity(snapshot, new SnapshotIntegrity(algorithm, keyId, CANONICAL_TAG_PLACEHOLDER)));
        byte[] canonicalPayload = canonicalize(tree);
        String tag;
        try {
            tag = hmac.sign(canonicalPayload, keyId, algorithm);
        } catch (SnapshotHmacException e) {
            throw new IdentitySnapshotCodecException("failed to sign identity snapshot", e, reasonFor(e));
        }

        // Write the authoritative tag back into the same canonical tree and emit those bytes, so the
        // stored form is exactly the signed canonical form with only the tag substituted.
        integrityNode(tree).put(TAG_FIELD, tag);
        return canonicalize(tree);
    }

    /**
     * Deserializes and verifies a previously encoded snapshot.
     *
     * @param encoded the UTF-8 JSON bytes previously produced by {@link #encode(IdentitySnapshot)}
     * @return the decoded, HMAC-verified, freshness-checked {@link IdentitySnapshot}
     * @throws IdentitySnapshotCodecException if the bytes cannot be parsed, carry an
     *                                         unknown-newer {@code schemaVersion}, fail HMAC
     *                                         verification, or fail the freshness policy (a stale or
     *                                         malformed temporal envelope)
     */
    public IdentitySnapshot decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ObjectNode root = parseTree(encoded);
        int schemaVersion = readSchemaVersion(root);
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw new IdentitySnapshotCodecException(
                    "unknown identity snapshot schemaVersion " + schemaVersion + "; this codec supports up to "
                            + SUPPORTED_SCHEMA_VERSION,
                    SnapshotDegradationReason.SCHEMA_INCOMPATIBLE);
        }

        // Verify the MAC over the parsed tree (the exact stored representation), then bind the same
        // tree to the typed model for the caller — the model is never re-serialized to recompute the
        // canonical bytes, so lossy Object-bound attribute values cannot invalidate a legitimate tag.
        // A validly-signed stored row carrying duplicate amr elements passes this tree-level check,
        // but the typed model's set semantics dedupe it, so such a row permanently fails the
        // verifyIntegrity/verifyForUse re-serialization — deny-direction by design; the framework
        // encoder cannot produce duplicates.
        verifyTree(root);
        IdentitySnapshot snapshot = treeToSnapshot(root);

        // Freshness is enforced only AFTER the MAC verifies, so a forged temporal envelope cannot be
        // trusted: an attacker who cannot forge the tag cannot backdate/extend the signed bounds, and
        // a legitimately-signed-but-stale row still fails closed here.
        freshnessPolicy.check(snapshot);
        return snapshot;
    }

    /**
     * Returns the current instant from the freshness policy's clock — the single time source the
     * durable encoder uses to mint a snapshot envelope's {@code issuedAt}, so encode-time and
     * decode-time freshness read the same (injectable) clock.
     *
     * @return the current instant per the codec's clock
     */
    Instant now() {
        return freshnessPolicy.clock().instant();
    }

    /**
     * Computes the {@code expiresAt} the durable encoder signs into a snapshot envelope for content
     * captured at {@code capturedAt}: {@code capturedAt + maxSnapshotLifetime} when a
     * snapshot-lifetime budget is configured, otherwise {@code capturedAt +} a documented 24-hour
     * default.
     *
     * <p>Both terms are anchored on the immutable {@link IdentitySnapshotContent#capturedAt()} —
     * never on the envelope's {@code issuedAt} — so a chained re-encode (which mints a fresh
     * {@code issuedAt} for a new carrier, see {@code IdentitySnapshotDurableEncoder}'s re-encode
     * path) cannot renew the signed expiry window past what the original capture already earned.
     *
     * @param capturedAt the content's immutable capture instant
     * @return the envelope expiry instant
     */
    Instant envelopeExpiry(Instant capturedAt) {
        return freshnessPolicy
                .maxSnapshotLifetime()
                .map(capturedAt::plus)
                .orElse(capturedAt.plus(DEFAULT_ENVELOPE_TTL));
    }

    /**
     * Parses the raw bytes into a mutable {@link ObjectNode} tree, both the verification input and
     * the source for the typed model returned to the caller.
     *
     * @param encoded the UTF-8 JSON bytes to parse
     * @return the parsed JSON object tree
     * @throws IdentitySnapshotCodecException if the bytes are not valid JSON or not a JSON object
     */
    private ObjectNode parseTree(byte[] encoded) {
        JsonNode root;
        try {
            root = objectMapper.readTree(encoded);
        } catch (IOException e) {
            throw new IdentitySnapshotCodecException("failed to parse identity snapshot bytes", e);
        }
        if (!(root instanceof ObjectNode objectNode)) {
            throw new IdentitySnapshotCodecException("identity snapshot is not a JSON object");
        }
        return objectNode;
    }

    /**
     * Reads the {@code schemaVersion} field from the parsed JSON tree.
     *
     * @param root the parsed JSON tree
     * @return the {@code schemaVersion} value
     * @throws IdentitySnapshotCodecException if the field is missing
     */
    private int readSchemaVersion(JsonNode root) {
        JsonNode schemaVersionNode = root.get(SCHEMA_VERSION_FIELD);
        if (schemaVersionNode == null) {
            throw new IdentitySnapshotCodecException("identity snapshot is missing schemaVersion");
        }
        return schemaVersionNode.asInt();
    }

    /**
     * Binds the parsed JSON tree to a typed {@link IdentitySnapshot} for the caller, after its MAC
     * has already been verified over the same tree.
     *
     * @param root the parsed, verified JSON object tree
     * @return the deserialized snapshot
     * @throws IdentitySnapshotCodecException if binding fails
     */
    private IdentitySnapshot treeToSnapshot(ObjectNode root) {
        try {
            return objectMapper.treeToValue(root, IdentitySnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IdentitySnapshotCodecException("failed to decode identity snapshot", e);
        }
    }

    /**
     * Verifies the MAC carried by the given parsed snapshot tree against the configured
     * {@link SnapshotHmac} keyset, recomputing the tag over the tree's own canonical bytes (with
     * {@code integrity.tag} swapped for the fixed placeholder) — the canonicalization
     * {@link #decode(byte[])} shares with {@link #encode(IdentitySnapshot)}. The verification
     * operates on a deep copy so the original {@code root} keeps its authoritative tag for the
     * caller's typed model.
     *
     * @param root the parsed snapshot tree to verify; must not be {@code null}
     * @throws IdentitySnapshotCodecException if the integrity envelope is malformed, the tag fails
     *                                         verification, or the keyset rejects the tag's key id
     */
    private void verifyTree(ObjectNode root) {
        ObjectNode integrity = integrityNode(root);
        String algorithm = textField(integrity, ALGORITHM_FIELD);
        String keyId = textField(integrity, KEY_ID_FIELD);
        String tag = textField(integrity, TAG_FIELD);

        ObjectNode canonicalTree = root.deepCopy();
        integrityNode(canonicalTree).put(TAG_FIELD, CANONICAL_TAG_PLACEHOLDER);
        verifyCanonical(canonicalize(canonicalTree), algorithm, keyId, tag);
    }

    /**
     * Verifies the given snapshot's {@link IdentitySnapshot#integrity()} envelope against the
     * configured {@link SnapshotHmac} keyset, recomputing the MAC over the same canonical bytes
     * {@link #encode(IdentitySnapshot)} signs and {@link #decode(byte[])} verifies — the single
     * canonicalization logic every verification entry point reuses.
     *
     * <p>This checks integrity only, never freshness. A caller reconstructing a
     * {@link dev.vertique.security.SecurityContext}
     * from a typed snapshot (rather than freshly decoding stored bytes) must use
     * {@link #verifyForUse(IdentitySnapshot)} instead, so a snapshot that was fresh when originally
     * decoded but has since passed its effective expiry is still rejected fail-closed.
     *
     * <p>Because this entry point receives a typed model rather than the stored bytes, it derives the
     * canonical tree by serializing the model and re-parsing it ({@link #canonicalTree(IdentitySnapshot)}),
     * which normalizes every {@code Map<String,Object>} attribute value through the identical
     * JSON->tree binding {@link #decode(byte[])} sees — so a snapshot verifies here byte-for-byte the
     * same way it verifies through {@code decode}, even for value types (e.g. {@code BigDecimal}) that
     * are lossy under Jackson's default Object binding.
     *
     * @param snapshot the snapshot to verify; must not be {@code null}
     * @throws IdentitySnapshotCodecException if the tag fails verification, or the keyset rejects
     *                                         the snapshot's key id
     */
    public void verifyIntegrity(IdentitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SnapshotIntegrity integrity = snapshot.integrity();
        ObjectNode tree = canonicalTree(withIntegrity(
                snapshot, new SnapshotIntegrity(integrity.algorithm(), integrity.keyId(), CANONICAL_TAG_PLACEHOLDER)));
        verifyCanonical(canonicalize(tree), integrity.algorithm(), integrity.keyId(), integrity.tag());
    }

    /**
     * Verifies both a typed snapshot's integrity envelope and its <em>current</em> freshness — the
     * single verification path a snapshot-reconstruction entry point (e.g.
     * {@link dev.vertique.security.IdentityReconstruction},
     * {@link dev.vertique.security.CapturedAuthorityReconstruction}) must use before minting any
     * {@link dev.vertique.security.SecurityContext}, rather than {@link #verifyIntegrity(IdentitySnapshot)}
     * alone.
     *
     * <p>{@link #decode(byte[])} enforces both dimensions on every byte-level decode; this method
     * enforces the same pair on an already-decoded, in-memory {@link IdentitySnapshot} using the exact
     * same {@link #verifyIntegrity(IdentitySnapshot)} and {@link SnapshotFreshnessPolicy#check} calls,
     * re-evaluated against the codec's <em>current</em> clock. A snapshot that verified fresh when it
     * was originally decoded but has since aged past its effective expiry (the three-term minimum of
     * its signed {@code expiresAt}, carrier-lifetime budget, and capture-anchored snapshot-lifetime
     * budget) is rejected fail-closed here even though its integrity tag remains valid — closing the
     * replay window a bare integrity check would leave open for a snapshot retained past its expiry.
     *
     * <p>This codec's {@link #freshnessPolicy} field is never {@code null} — both constructors require
     * one, defaulting to an unbudgeted, system-clock policy when the caller supplies none — so freshness
     * is unconditionally enforced here exactly as it is in {@link #decode(byte[])}, with no weaker
     * "no policy configured" fallback.
     *
     * @param snapshot the snapshot to verify; must not be {@code null}
     * @throws IdentitySnapshotCodecException if the integrity tag fails verification (see
     *                                         {@link #verifyIntegrity(IdentitySnapshot)}), or the
     *                                         snapshot's current freshness fails per
     *                                         {@link SnapshotFreshnessPolicy#check}
     */
    public void verifyForUse(IdentitySnapshot snapshot) {
        verifyIntegrity(snapshot);
        freshnessPolicy.check(snapshot);
    }

    /**
     * Recomputes the MAC over {@code canonicalPayload} under {@code keyId}/{@code algorithm} and
     * constant-time-compares it to {@code tag}, failing closed with the mapped degradation reason —
     * the shared verification tail of {@link #verifyTree(ObjectNode)} and
     * {@link #verifyIntegrity(IdentitySnapshot)}.
     *
     * @param canonicalPayload the canonical bytes the tag is expected to sign over
     * @param algorithm        the MAC algorithm the tag claims to be signed under
     * @param keyId            the key id the tag claims to be signed under
     * @param tag              the base64url-encoded MAC tag to verify
     * @throws IdentitySnapshotCodecException if the tag is invalid, or the keyset rejects the
     *                                         algorithm/key id
     */
    private void verifyCanonical(byte[] canonicalPayload, String algorithm, String keyId, String tag) {
        boolean valid;
        try {
            valid = hmac.verify(canonicalPayload, keyId, algorithm, tag);
        } catch (SnapshotHmacException e) {
            throw new IdentitySnapshotCodecException(
                    "identity snapshot integrity verification failed", e, reasonFor(e));
        }
        if (!valid) {
            throw new IdentitySnapshotCodecException(
                    "identity snapshot integrity tag is invalid", SnapshotDegradationReason.BAD_HMAC);
        }
    }

    /**
     * Maps a {@link SnapshotHmacException}'s {@link SnapshotHmacException.Kind} to the
     * corresponding {@link SnapshotDegradationReason}.
     *
     * @param e the HMAC exception thrown by {@link SnapshotHmac}; must not be {@code null}
     * @return {@link SnapshotDegradationReason#KEY_UNAVAILABLE} when no key is configured at all,
     *         {@link SnapshotDegradationReason#BAD_HMAC} when the envelope declared an algorithm
     *         outside the server allowlist (an alg-confusion / tamper signal), otherwise
     *         {@link SnapshotDegradationReason#UNKNOWN_KEY}
     */
    private static SnapshotDegradationReason reasonFor(SnapshotHmacException e) {
        return switch (e.kind()) {
            case KEY_UNAVAILABLE -> SnapshotDegradationReason.KEY_UNAVAILABLE;
            case UNSUPPORTED_ALGORITHM -> SnapshotDegradationReason.BAD_HMAC;
            case UNKNOWN_KEY -> SnapshotDegradationReason.UNKNOWN_KEY;
        };
    }

    /**
     * Serializes the given snapshot and re-parses it into a mutable JSON object tree, normalizing
     * every {@code Map<String,Object>} attribute value through the same JSON->tree binding the
     * verifier applies to the stored bytes. Canonical bytes are always taken from this tree — never
     * by re-serializing a deserialized model — so a value's stored representation is exactly what is
     * MAC'd on both the sign and verify sides.
     *
     * @param snapshot the snapshot (carrying the intended algorithm/keyId and a placeholder tag)
     *                 whose canonical tree is being built
     * @return the parsed, mutable canonical tree
     * @throws IdentitySnapshotCodecException if serialization or re-parsing fails, or the snapshot
     *                                         does not serialize to a JSON object
     */
    private ObjectNode canonicalTree(IdentitySnapshot snapshot) {
        try {
            byte[] modelBytes = objectMapper.writeValueAsBytes(snapshot);
            return parseTree(modelBytes);
        } catch (IOException e) {
            throw new IdentitySnapshotCodecException("failed to compute canonical identity snapshot payload", e);
        }
    }

    /**
     * Serializes the given JSON tree to its canonical bytes — the single canonicalization both the
     * sign and verify sides share, so their MAC payloads are byte-identical for equal trees.
     *
     * @param tree the canonical tree (with {@code integrity.tag} set to the placeholder) to serialize
     * @return the canonical bytes used as the HMAC payload
     * @throws IdentitySnapshotCodecException if serialization fails
     */
    private byte[] canonicalize(JsonNode tree) {
        try {
            return objectMapper.writeValueAsBytes(tree);
        } catch (IOException e) {
            throw new IdentitySnapshotCodecException("failed to canonicalize identity snapshot payload", e);
        }
    }

    /**
     * Returns the {@code integrity} envelope object node of the given snapshot tree, failing closed
     * when it is absent or not a JSON object.
     *
     * @param tree the snapshot tree
     * @return the mutable {@code integrity} object node
     * @throws IdentitySnapshotCodecException if the envelope is missing or not a JSON object
     */
    private ObjectNode integrityNode(JsonNode tree) {
        if (tree.get(INTEGRITY_FIELD) instanceof ObjectNode integrity) {
            return integrity;
        }
        throw new IdentitySnapshotCodecException("identity snapshot is missing its integrity envelope");
    }

    /**
     * Reads a required textual field from the integrity envelope, failing closed when it is absent
     * or not a JSON string.
     *
     * @param integrity the integrity envelope object node
     * @param field     the field name to read
     * @return the field's text value
     * @throws IdentitySnapshotCodecException if the field is missing or not textual
     */
    private String textField(ObjectNode integrity, String field) {
        JsonNode value = integrity.get(field);
        if (value == null || !value.isTextual()) {
            throw new IdentitySnapshotCodecException(
                    "identity snapshot integrity envelope is missing textual field '" + field + "'");
        }
        return value.asText();
    }

    /**
     * Returns a copy of {@code snapshot} with its {@link IdentitySnapshot#integrity()} replaced by
     * {@code integrity}, all other fields unchanged.
     *
     * @param snapshot  the snapshot to copy
     * @param integrity the replacement integrity envelope
     * @return a new {@link IdentitySnapshot} identical to {@code snapshot} except for
     *         {@code integrity}
     */
    private IdentitySnapshot withIntegrity(IdentitySnapshot snapshot, SnapshotIntegrity integrity) {
        return new IdentitySnapshot(
                snapshot.schemaVersion(),
                snapshot.content(),
                snapshot.carrier(),
                snapshot.issuedAt(),
                snapshot.expiresAt(),
                integrity);
    }
}
