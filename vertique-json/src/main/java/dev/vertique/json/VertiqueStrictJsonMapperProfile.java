// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import java.io.IOException;
import java.math.BigDecimal;

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
     * clean, checked Jackson mapping exception the value-side serializer uses. The message never
     * echoes the offending key's digits (log-injection hygiene).
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
         *     message names the bound and the offending scale/precision/length, never the key's digits
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
}
