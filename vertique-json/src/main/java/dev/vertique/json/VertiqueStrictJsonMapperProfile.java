// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
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
 *       notation, trailing zeros (the scale) preserved exactly.
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
        m.registerModule(strict);
        return m;
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
