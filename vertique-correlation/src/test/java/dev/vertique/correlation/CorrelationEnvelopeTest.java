// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.correlation.CorrelationEnvelope.EnvelopeProtocolCorrelation;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JacksonDefaults;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorrelationEnvelope}'s JSON shape and the
 * {@link CorrelationDurableKeys} constants.
 *
 * <p>The shape is asserted against {@code io.vertx.core.json.jackson.DatabindCodec.mapper()},
 * which is the mapper the durable encoder/decoder use at runtime.
 *
 * <p>TP-002 (T024) additionally proves {@link CorrelationContextDurableDecoder#decode} resolves
 * {@link VertiqueJson#mapper()} at use time rather than the raw, install-blind
 * {@code DatabindCodec#mapper()}, and that the decode is tolerant of which registered profile
 * encoded the bytes. {@code @AfterEach} restores the raw Vert.x delegate so the process-wide
 * install never leaks across tests (the reset seam is opened by this module's {@code <build>}
 * argLine, {@code -Dvertique.json.codec.allowReset=true}).
 */
class CorrelationEnvelopeTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "http-header");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "http-header");

    private static final DurableDecodeContext DECODE_CTX = new DurableDecodeContext("kafka");

    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- CorrelationDurableKeys ---

    @Test
    @DisplayName("CorrelationDurableKeys.CORRELATION is the short namespace name 'correlation'")
    void durableKeyConstant() {
        // The namespace is the short name owned by the encoder/decoder. The vertique- prefix is
        // added only at the Kafka wire boundary by DurableMetadataHeaderCodec.toHeaders().
        assertEquals("correlation", CorrelationDurableKeys.CORRELATION);
    }

    // --- CURRENT_SCHEMA_VERSION ---

    @Test
    @DisplayName("CURRENT_SCHEMA_VERSION is 1 in PR2")
    void schemaVersionConstant() {
        assertEquals(1, CorrelationEnvelope.CURRENT_SCHEMA_VERSION);
    }

    // --- JSON shape: minimal envelope ---

    @Test
    @DisplayName("minimal envelope (requestId + correlationId only) serialises to those keys + schemaVersion")
    void minimalEnvelopeJsonShape() throws Exception {
        CorrelationEnvelope env = new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION, REQ_ID, CORR_ID, null, null, null, null, null);
        String json = DatabindCodec.mapper().writeValueAsString(env);
        JsonObject obj = new JsonObject(json);

        assertEquals(1, obj.getInteger("schemaVersion"));
        assertNotNull(obj.getJsonObject("requestId"));
        assertNotNull(obj.getJsonObject("correlationId"));
        // NON_NULL keeps optional keys out of the wire shape entirely.
        assertFalse(obj.containsKey("causationId"), "causationId omitted when null");
        assertFalse(obj.containsKey("trace"), "trace omitted when null");
        assertFalse(obj.containsKey("session"), "session omitted when null");
        assertFalse(obj.containsKey("protocolCorrelations"), "protocolCorrelations omitted when null");
        assertFalse(obj.containsKey("attributes"), "attributes omitted when null");
    }

    @Test
    @DisplayName("envelope round-trips through Jackson preserving equality")
    void envelopeRoundTrip() throws Exception {
        EnvelopeProtocolCorrelation refDto = new EnvelopeProtocolCorrelation(
                "X-FAPI-Interaction-ID",
                "b7f65c7e-4f42-4e7c-9d97-5e8f0d86a111",
                "http-header",
                CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122,
                CorrelationPropagationMode.NONE,
                true,
                Map.of("standard", "fapi"));
        CorrelationSessionRef session =
                new CorrelationSessionRef("sess-1", "jwt-sid", "jwt-claim", "sid", true, Map.of());
        CorrelationEnvelope env = new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION,
                REQ_ID,
                CORR_ID,
                new CorrelationIdentifier("cause-1", "upstream"),
                null,
                session,
                List.of(refDto),
                Map.of("k", "v"));

        String json = DatabindCodec.mapper().writeValueAsString(env);
        CorrelationEnvelope parsed = DatabindCodec.mapper().readValue(json, CorrelationEnvelope.class);
        assertEquals(env, parsed);
    }

    @Test
    @DisplayName("envelope ignores unknown JSON properties (forward compatibility)")
    void unknownPropertiesIgnored() throws Exception {
        String json = "{\"schemaVersion\":1," + "\"requestId\":{\"value\":\"r\",\"source\":\"src\"},"
                + "\"correlationId\":{\"value\":\"c\",\"source\":\"src\"},"
                + "\"unknownField\":\"future-value\","
                + "\"trace\":null}";
        CorrelationEnvelope parsed = DatabindCodec.mapper().readValue(json, CorrelationEnvelope.class);
        assertEquals(1, parsed.schemaVersion());
        assertEquals("r", parsed.requestId().value());
        assertNull(parsed.trace());
    }

    // --- TP-002 (T024): decoder follows the process mapper and tolerates profiles ---

    /**
     * TP-002: proves {@link CorrelationContextDurableDecoder#decode} resolves
     * {@link VertiqueJson#mapper()} at use time (not the raw, install-blind
     * {@code DatabindCodec#mapper()}), and that once the decoder follows the process mapper it stays
     * tolerant of which registered profile produced the bytes.
     *
     * <p><strong>Single-quote phase.</strong> {@link DurableMetadata} only ever stores an
     * already-materialized {@link JsonObject}; {@link JsonObject#encode()} always re-serializes to
     * standard, double-quoted JSON regardless of how the object was built or which mapper is
     * installed — so a single-quoted <em>document</em> can never reach the decoder's own
     * {@code readTree} call through the public {@code DurableMetadata} surface (its malformed syntax
     * is normalized away before the decoder ever sees it). To exercise the decoder's own mapper
     * resolution directly, {@code metadata} is a mock whose {@code body(namespace)} returns a
     * {@link RawTextJsonObject} — a {@link JsonObject} subclass overriding only {@code encode()} to
     * hand back the literal single-quoted text verbatim, bypassing the copy-and-reserialize step that
     * every real {@code DurableMetadata} accessor performs. This is a mock of the final
     * {@code DurableMetadata} class, hence this module's surefire {@code argLine} carries the Mockito
     * inline-mock-maker javaagent.
     *
     * <p>Given: the minimal envelope fixture encoded under the raw delegate (pinned bytes, decoded
     * once to the reference {@link CorrelationContextSnapshot}); a single-quoted variant of the same
     * content (delivered to the decoder via the {@code RawTextJsonObject} mock described above); the
     * sanctioned seed ({@code JacksonDefaults.applySystem(new ObjectMapper())}) with
     * {@code JsonParser.Feature.ALLOW_SINGLE_QUOTES} enabled, installed under a test id.
     * When: the decoder decodes the single-quoted variant before and after the install.
     * Then: before install, decoding fails with the decoder's own "malformed CorrelationEnvelope
     * JSON" warning; after install, it decodes to the pinned reference snapshot.
     *
     * <p><strong>Cross-profile phase.</strong> Given: the same envelope encoded under the registry's
     * {@code vertique} mapper and under its {@code system} mapper (both are ordinary, double-quoted
     * JSON — {@code CorrelationEnvelope} is {@code @JsonInclude(NON_NULL)} at the class level, so
     * both encodings omit the same null-valued optional keys as the raw pin).
     * When: the {@code vertique}-encoded bytes are decoded with {@code system} installed, and the
     * {@code system}-encoded bytes are decoded with {@code vertique} installed.
     * Then: both decodes yield the same {@link CorrelationContextSnapshot} as the raw pin, and
     * re-encoding the envelope under the raw delegate afterward still yields the exact pinned bytes —
     * proof that installing never mutates {@code DatabindCodec#mapper()} itself.
     *
     * <p><strong>Expected initial (RED) result:</strong> today's decoder always reads
     * {@code DatabindCodec.mapper()}, which never gains {@code ALLOW_SINGLE_QUOTES} regardless of any
     * install, so the post-install single-quote decode fails exactly like the pre-install one.
     *
     * <p><strong>Sensitivity proof:</strong> keeping {@code DatabindCodec.mapper()} in the decoder
     * leaves the post-install single-quote decode failing with the same "malformed CorrelationEnvelope
     * JSON" warning as the pre-install decode.
     */
    @Test
    @DisplayName("durableDecodingFollowsTheProcessMapperAndToleratesProfiles: decoder resolves"
            + " VertiqueJson.mapper() at decode time (single-quoted envelope binds only after install) and"
            + " stays tolerant of which profile encoded the bytes")
    void durableDecodingFollowsTheProcessMapperAndToleratesProfiles() throws Exception {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContextDurableDecoder decoder = new CorrelationContextDurableDecoder(factory);

        CorrelationEnvelope env = new CorrelationEnvelope(
                CorrelationEnvelope.CURRENT_SCHEMA_VERSION, REQ_ID, CORR_ID, null, null, null, null, null);

        // --- Pinned raw bytes: the raw (pre-install) delegate; decoded once for the reference snapshot. ---
        String rawJson = DatabindCodec.mapper().writeValueAsString(env);
        CorrelationContextSnapshot pinnedSnapshot = decodeToSnapshot(decoder, rawJson);

        // --- Single-quote phase ---
        String singleQuoted = "{'schemaVersion':1,"
                + "'requestId':{'value':'" + REQ_ID.value() + "','source':'" + REQ_ID.source() + "'},"
                + "'correlationId':{'value':'" + CORR_ID.value() + "','source':'" + CORR_ID.source() + "'}}";
        DurableMetadata singleQuotedMetadata = mock(DurableMetadata.class);
        when(singleQuotedMetadata.body(CorrelationDurableKeys.CORRELATION))
                .thenReturn(Optional.of(new RawTextJsonObject(singleQuoted)));

        ContextDecodeResult<CorrelationContext> beforeInstall = decoder.decode(singleQuotedMetadata, DECODE_CTX);
        assertFalse(beforeInstall.value().isPresent(), "single-quoted envelope must not decode before install");
        assertEquals(1, beforeInstall.warnings().size());
        assertTrue(
                beforeInstall.warnings().get(0).reason().contains("malformed CorrelationEnvelope JSON"),
                "expected the decoder's own malformed-JSON warning, was: "
                        + beforeInstall.warnings().get(0).reason());

        ObjectMapper lenientMapper = JacksonDefaults.applySystem(new ObjectMapper());
        lenientMapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        VertiqueJson.install(JsonProfileId.of("correlation-test-lenient"), lenientMapper);

        ContextDecodeResult<CorrelationContext> afterInstall = decoder.decode(singleQuotedMetadata, DECODE_CTX);
        assertTrue(
                afterInstall.value().isPresent(),
                "single-quoted envelope must decode once a lenient profile is installed");
        assertEquals(pinnedSnapshot, afterInstall.value().get().snapshot());

        VertiqueJson.resetForTests();

        // --- Cross-profile phase ---
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        String vertiqueJson = registry.mapper(JsonProfileId.of("vertique")).writeValueAsString(env);
        String systemJson = registry.mapper(JsonProfileId.SYSTEM).writeValueAsString(env);

        VertiqueJson.install(JsonProfileId.SYSTEM, registry.mapper(JsonProfileId.SYSTEM));
        assertEquals(pinnedSnapshot, decodeToSnapshot(decoder, vertiqueJson));
        VertiqueJson.resetForTests();

        VertiqueJson.install(JsonProfileId.of("vertique"), registry.mapper(JsonProfileId.of("vertique")));
        assertEquals(pinnedSnapshot, decodeToSnapshot(decoder, systemJson));
        VertiqueJson.resetForTests();

        // --- The raw delegate itself was never mutated by any install above. ---
        assertEquals(rawJson, DatabindCodec.mapper().writeValueAsString(env));
    }

    /**
     * Decodes {@code json} (wrapped under the {@code "correlation"} namespace via the real, public
     * {@link DurableMetadata} surface) and returns the rebuilt context's snapshot, failing the test if
     * the decode did not produce a value.
     */
    private static CorrelationContextSnapshot decodeToSnapshot(CorrelationContextDurableDecoder decoder, String json)
            throws Exception {
        JsonObject body = new JsonObject(json);
        ContextDecodeResult<CorrelationContext> result =
                decoder.decode(DurableMetadata.of(CorrelationDurableKeys.CORRELATION, body), DECODE_CTX);
        assertTrue(result.value().isPresent(), "expected a decoded value for: " + json);
        return result.value().get().snapshot();
    }

    /**
     * A {@link JsonObject} whose {@link #encode()} hands back arbitrary literal text instead of
     * re-serializing its (empty) backing map — used only to deliver a single-quoted document past
     * {@link DurableMetadata}'s copy-and-materialize accessors and into the decoder's own
     * {@code readTree} call, which no real {@code DurableMetadata} body can otherwise carry (see
     * {@link #durableDecodingFollowsTheProcessMapperAndToleratesProfiles}).
     */
    private static final class RawTextJsonObject extends JsonObject {
        private final String raw;

        RawTextJsonObject(String raw) {
            this.raw = raw;
        }

        @Override
        public String encode() {
            return raw;
        }
    }
}
