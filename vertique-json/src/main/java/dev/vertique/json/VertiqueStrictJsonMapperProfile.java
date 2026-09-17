// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Built-in {@code vertique-strict} profile: the {@code vertique} opinionated defaults plus the
 * strict-decimal / strict-string wire posture.
 *
 * <p>This is the third reserved built-in profile: it is always registered by the framework alongside
 * the {@code vertx} and {@code vertique} profiles, under the reserved id {@code vertique-strict}.
 * Like the {@code vertique} profile it owns its own {@link ObjectMapper} — built once from
 * {@link JacksonDefaults#apply(ObjectMapper)} — so it never mutates Vert.x's shared
 * {@code DatabindCodec.mapper()} (json-004).
 *
 * <p><strong>Strict posture.</strong> On top of the {@code vertique} defaults this profile registers
 * the three opt-in serdes as a single {@code vertique-strict} Jackson module:
 *
 * <ul>
 *   <li>{@link BigDecimalAsStringSerializer} — a typed {@link BigDecimal} property is written as a
 *       quoted JSON <em>string</em> via {@link BigDecimal#toPlainString()}: never scientific
 *       notation, and numerically exact — the scale is preserved exactly for a non-negative scale,
 *       while a negative-scale value is written in expanded plain form and re-reads with scale 0
 *       (json-004 R2-4).
 *   <li>{@link BigDecimalStrictStringDeserializer} — a typed {@link BigDecimal} property is read
 *       <em>only</em> from a JSON string matching the bounded plain-decimal grammar
 *       {@code -?[0-9]+(\.[0-9]+)?} of at most 100 characters. JSON numbers, exponent forms,
 *       whitespace-padded and over-length literals are all rejected with a
 *       {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}. The two form a matched
 *       pair, so every decimal this profile writes re-parses through it unchanged.
 *   <li>{@link StrictStringDeserializer} — a {@link String} property rejects scalar coercion: a JSON
 *       number or boolean targeting a {@code String} fails instead of silently becoming
 *       {@code "42"} / {@code "true"}.
 * </ul>
 *
 * <p><strong>{@code BigDecimal} map keys are bounded too (json-004).</strong> A {@code Map<BigDecimal,
 * ?>} key is parsed by the nested {@link BigDecimalKeyDeserializer}, registered on the same
 * {@code vertique-strict} module via {@code SimpleModule.addKeyDeserializer}. It enforces the exact
 * same bounded plain-decimal grammar as {@link BigDecimalStrictStringDeserializer} — by delegating to
 * the shared {@link BigDecimalStrictStringDeserializer#parseBounded(String)} helper — so a JSON object
 * key cannot smuggle an exponent-notation literal (e.g. {@code "1e-2000000000"}) past the value-side
 * bound and amplify into a huge-scale {@code BigDecimal} on the way back out.
 *
 * <p><strong>{@code BigDecimal} map keys are written in bounded plain form too (json-004 R2-3).</strong>
 * The nested {@link BigDecimalKeySerializer}, registered via {@code SimpleModule.addKeySerializer},
 * writes a {@code Map<BigDecimal, ?>} key through the same
 * {@link BigDecimalAsStringSerializer#boundedPlainString(BigDecimal)} bound that the value-side
 * serializer uses. Without this, Jackson's default key serializer writes {@link BigDecimal#toString()},
 * which uses scientific notation for a small-scale value (e.g. {@code "0.0000001"} → {@code "1E-7"}) —
 * a form {@link BigDecimalKeyDeserializer} would then reject, so the profile could not read back its
 * own output. The same 100-character bound applies to a key as to a value: switching keys to
 * {@code toPlainString()} without it would newly expose the egress amplification the value-side bound
 * already prevents, since {@code toString()} stays compact for a huge-scale value where
 * {@code toPlainString()} would not.
 *
 * <p><strong>A repeated identical key is a parse error.</strong> This profile's mapper enables
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}; the {@code vertique} and {@code system}
 * mappers are unchanged and keep the last value. Because a REST route parses raw body bytes with its
 * effective profile's mapper, a route under {@code vertique-strict} answers 400 to a body repeating a
 * key before the resource runs — except where that mapper is also the installed process codec, in
 * which case the binder swallows the parse rejection and binds the raw buffer, and only a synthesized
 * body schema under {@code web-validation} still produces the 400. An application selecting this
 * profile as {@code json.systemProfile} installs the same mapper as the process JSON codec, so the
 * codec's delegated decode methods reject a repeated key too, while its streaming overloads and the
 * static {@code DatabindCodec} parser helpers keep accepting it, as that codec's own contract
 * documents. The MCP envelope codec already rejects a repeated key under every profile.
 *
 * <p>The same feature is the signal JSON-005 reads to decide how it describes several
 * {@code @JsonAlias} spellings of one property: under this profile they are published as mutually
 * exclusive, so a body carrying a property under two spellings is rejected at the gate, while the
 * binder itself accepts it under every profile. No separate flag or configuration key decides that.
 *
 * <p><strong>{@code USE_BIG_DECIMAL_FOR_FLOATS} is deliberately disabled</strong> (the
 * {@code vertique} defaults enable it; this profile turns it back off). That keeps <em>untyped</em>
 * decimal pass-through symmetric: a decimal read into an untyped target ({@code Object},
 * {@code Map<String, Object>}, {@code JsonObject}) stays a JSON number on the way back out —
 * number-in, number-out. Were it left enabled, an untyped decimal would bind to a {@link BigDecimal}
 * and then be re-serialized by {@link BigDecimalAsStringSerializer} as a <em>string</em>, silently
 * changing the wire shape of documents this profile merely relays.
 *
 * <p><strong>The precision boundary that buys.</strong> Typed {@link BigDecimal} fields keep full
 * precision and require the string wire form in both directions. <em>Untyped</em> decimals instead
 * bind as {@code double}, so a value beyond IEEE-754 double precision loses digits when relayed
 * through an untyped target. Applications that must preserve arbitrary precision declare the
 * property as {@link BigDecimal}; untyped relay is explicitly a lossy-but-shape-stable path.
 *
 * <p>This type is package-private, is seeded separately from application-contributed profiles, and
 * is <strong>probe-exempt</strong>: the registry's structural round-trip probe round-trips a
 * {@code JsonObject} carrying a null field, which the inherited {@code NON_NULL} inclusion would
 * drop. Applications must not contribute a profile with the reserved {@code vertique-strict} id
 * (json-004).
 *
 * @see VertiqueJsonMapperProfile
 * @see JacksonDefaults
 */
final class VertiqueStrictJsonMapperProfile implements JsonMapperProfile {

    /** The reserved id this built-in profile registers under. */
    static final JsonProfileId ID = JsonProfileId.of("vertique-strict");

    /**
     * The single independent {@link ObjectMapper} backing this profile, built once at construction.
     * It is never the shared Vert.x {@code DatabindCodec.mapper()} (json-004).
     */
    private final ObjectMapper mapper = buildMapper();

    /**
     * The single JSON Schema type override this profile declares (FR-JSON-089): a {@code BigDecimal}
     * {@link JsonSchemaTypeOverride.Direction#BOTH} override describing the bounded, string-typed
     * wire form {@link BigDecimalAsStringSerializer} and {@link BigDecimalStrictStringDeserializer}
     * actually produce and accept. Built once, statically, and returned as the same stable,
     * unmodifiable one-element list on every call to {@link #jsonSchemaTypeOverrides()}.
     */
    private static final List<JsonSchemaTypeOverride> SCHEMA_OVERRIDES =
            List.of(JsonSchemaTypeOverride.both(BigDecimal.class, buildBigDecimalFragment()));

    /**
     * Builds the {@code BigDecimal} schema fragment {@link #SCHEMA_OVERRIDES} declares, from the
     * shared {@link BigDecimalStrictStringDeserializer} grammar constants — never a second,
     * independently maintained copy of the bound or the pattern, so the published schema and the
     * serde's accepted grammar cannot drift apart (FR-JSON-089).
     *
     * <p>The fragment describes the wire form both strict {@code BigDecimal} serdes agree on: a JSON
     * string ({@code "type":"string"}), tagged {@code "format":"decimal"}, bounded to
     * {@link BigDecimalStrictStringDeserializer#MAX_LENGTH} characters, matching
     * {@link BigDecimalStrictStringDeserializer#ANCHORED_PLAIN_DECIMAL_PATTERN}.
     *
     * @return the immutable {@link JsonSchemaFragment} describing the strict {@code BigDecimal} wire
     *     form
     */
    private static JsonSchemaFragment buildBigDecimalFragment() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", "string");
        node.put("format", "decimal");
        node.put("maxLength", BigDecimalStrictStringDeserializer.MAX_LENGTH);
        node.put("pattern", BigDecimalStrictStringDeserializer.ANCHORED_PLAIN_DECIMAL_PATTERN);
        return JsonSchemaFragment.parse(node.toString());
    }

    /**
     * Builds this profile's mapper: the {@code vertique} defaults, then the strict overlay.
     *
     * <p>Order matters. {@link JacksonDefaults#apply(ObjectMapper)} runs first (it re-registers
     * Vert.x's Jackson module last so Vert.x's {@code Instant} serializer stays authoritative), then
     * {@code USE_BIG_DECIMAL_FOR_FLOATS} is disabled so untyped decimals keep the number wire shape,
     * and finally the strict serdes are registered as one {@code vertique-strict} module — a later
     * {@link SimpleModule} wins over the serializers/deserializers registered before it.
     *
     * @return a freshly configured, profile-owned {@link ObjectMapper}
     */
    private static ObjectMapper buildMapper() {
        ObjectMapper m = JacksonDefaults.apply(new ObjectMapper());
        // Untyped decimals must stay JSON numbers: binding them to BigDecimal would route them
        // through BigDecimalAsStringSerializer on the way out and silently restring the wire shape.
        m.disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // A repeated identical key is a parse error under this profile, where every other built-in
        // mapper keeps the last value. It is also the signal JSON-005 reads to decide that several
        // @JsonAlias spellings of one property are published as mutually exclusive, so the profile's
        // schema rule and its parser agree by construction rather than through a second flag.
        m.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        SimpleModule strict = new SimpleModule("vertique-strict");
        strict.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
        strict.addDeserializer(BigDecimal.class, new BigDecimalStrictStringDeserializer());
        strict.addDeserializer(String.class, new StrictStringDeserializer());
        strict.addKeySerializer(BigDecimal.class, new BigDecimalKeySerializer());
        strict.addKeyDeserializer(BigDecimal.class, new BigDecimalKeyDeserializer());
        m.registerModule(strict);
        return m;
    }

    // --- BigDecimal map-key bound (json-004) ---

    /**
     * Bounded {@code BigDecimal} key {@link JsonSerializer} for {@code Map<BigDecimal, ?>} keys under
     * the {@code vertique-strict} profile (json-004 R2-3).
     *
     * <p>Delegates to the shared {@link BigDecimalAsStringSerializer#boundedPlainString(BigDecimal)}
     * helper — the same write-side length bound a {@code BigDecimal} <em>value</em> is held to —
     * writing the key via {@link JsonGenerator#writeFieldName(String)} rather than
     * {@link JsonGenerator#writeString(String)}. Without this, Jackson's default key serializer for
     * {@link BigDecimal} writes {@link BigDecimal#toString()}, which uses scientific notation for a
     * small-scale value; {@link BigDecimalKeyDeserializer} would then reject that form on read, so the
     * profile could not read back its own output. The bound must be enforced here too: switching keys
     * to the unbounded {@code toPlainString()} would newly expose the egress amplification the
     * value-side bound already prevents, since {@code toString()} stays compact for a huge-scale value
     * where {@code toPlainString()} would not.
     *
     * <p>A rejection is surfaced via {@link JsonMappingException#from(JsonGenerator, String)}, the same
     * clean, checked Jackson mapping exception the value-side serializer uses. The message this class
     * produces is value-free: it names only the bound and the offending scale/precision/length, never
     * the key's digits. Jackson then appends its own reference chain — {@code MapSerializer} catches
     * the throw and calls {@code wrapAndThrow(provider, e, value, String.valueOf(keyElem))} under the
     * default {@link com.fasterxml.jackson.databind.SerializationFeature#WRAP_EXCEPTIONS} — so the
     * final {@link JsonMappingException#getMessage()} <strong>does</strong> carry the key's
     * {@link BigDecimal#toString()} form in the {@code "(through reference chain: …)"} suffix. That
     * suffix is bounded by the key's precision, never by the plain-form expansion this bound rejects,
     * since {@code toString()} stays compact for a huge scale, and it contains only
     * {@code [0-9.\-E+]} characters, so no control characters can ride along. The value-side serializer
     * has no such exposure: there the chain names the enclosing property, not the rejected value.
     */
    private static final class BigDecimalKeySerializer extends JsonSerializer<BigDecimal> {

        /**
         * Writes {@code value} as a bounded plain-decimal JSON object field name.
         *
         * @param value      the {@link BigDecimal} map key to write; never {@code null}
         * @param gen        the {@link JsonGenerator} to write into
         * @param serializers the active {@link SerializerProvider} (unused)
         * @throws IOException if the underlying generator throws, or {@code value}'s plain-string form
         *     would exceed {@value BigDecimalStrictStringDeserializer#MAX_LENGTH} characters; the
         *     message names the bound and the offending scale/precision/length; Jackson's reference
         *     chain, appended downstream, additionally carries the key's {@code toString()} form — see
         *     the class javadoc
         */
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            try {
                gen.writeFieldName(BigDecimalAsStringSerializer.boundedPlainString(value));
            } catch (IllegalArgumentException rejected) {
                throw JsonMappingException.from(gen, rejected.getMessage());
            }
        }
    }

    /**
     * Bounded {@link KeyDeserializer} for {@code Map<BigDecimal, ?>} keys under the
     * {@code vertique-strict} profile.
     *
     * <p>Delegates to the shared {@link BigDecimalStrictStringDeserializer#parseBounded(String)}
     * helper, so a map key is held to the exact same bounded plain-decimal grammar
     * ({@code -?[0-9]+(\.[0-9]+)?}, at most {@value BigDecimalStrictStringDeserializer#MAX_LENGTH}
     * characters) as a {@code BigDecimal} value — a JSON object key cannot bypass the value-side
     * grammar bound and smuggle an exponent-notation literal into an amplification-prone
     * {@code BigDecimal}.
     *
     * <p>A rejection is surfaced via {@link JsonMappingException#from(DeserializationContext, String)},
     * the same clean, checked Jackson mapping exception the deserializer's grammar-mismatch branch
     * uses — never a raw {@link NumberFormatException} or an unchecked type that would surface as an
     * uncaught server error. The message never echoes the rejected key text (log-injection hygiene).
     */
    private static final class BigDecimalKeyDeserializer extends KeyDeserializer {

        /**
         * Parses {@code key} as a bounded plain-decimal {@link BigDecimal}.
         *
         * @param key  the JSON object's raw key text
         * @param ctxt the active deserialization context, used only for clean error reporting
         * @return the parsed {@link BigDecimal}
         * @throws IOException if {@code key} violates the bounded plain-decimal grammar; the message
         *     names the bound and the rejected key's length, never the key text itself
         */
        @Override
        public Object deserializeKey(String key, DeserializationContext ctxt) throws IOException {
            try {
                return BigDecimalStrictStringDeserializer.parseBounded(key);
            } catch (IllegalArgumentException rejected) {
                throw JsonMappingException.from(ctxt, rejected.getMessage());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return the reserved {@code vertique-strict} id
     */
    @Override
    public JsonProfileId id() {
        return ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the single independent {@link ObjectMapper} this profile owns — built once from the
     * {@code vertique} defaults plus the strict overlay and returned as-is, never copied or wrapped.
     * This mapper is the profile's own instance, so the {@code vertique-strict} profile never mutates
     * Vert.x's shared {@code DatabindCodec.mapper()} (json-004).
     *
     * @return the {@link ObjectMapper} backing the {@code vertique-strict} profile
     */
    @Override
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the same stable, unmodifiable one-element list on every call: a single
     * {@code BigDecimal} {@link JsonSchemaTypeOverride.Direction#BOTH} override built from the
     * {@link BigDecimalStrictStringDeserializer} grammar constants (FR-JSON-089).
     *
     * @return the single-element list containing the {@code BigDecimal} override
     */
    @Override
    public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
        return SCHEMA_OVERRIDES;
    }
}
