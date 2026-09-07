// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;

/**
 * Built-in {@code system} profile — the baseline every other profile layers on.
 *
 * <p>Its mapper is a {@link ObjectMapper#copy() copy} of Vert.x's shared
 * {@link DatabindCodec#mapper()} carrying {@link JacksonDefaults#applySystem(ObjectMapper)}. The
 * copy inherits every Vert.x seed setting (including {@code JsonParser.Feature.ALLOW_COMMENTS} and
 * the operator-tuned {@code vertx.jackson.defaultRead*} stream-read limits) and gains
 * {@code Jdk8Module}, {@code JavaTimeModule} and ISO-8601 date rendering, with Vert.x's Jackson
 * module re-registered last so {@code Instant} stays byte-identical to Vert.x's own output. The
 * shared mapper itself is <strong>never</strong> exposed or mutated.
 *
 * <p>Result: byte-identical to the raw Vert.x mapper for every type that mapper could already
 * handle, except {@code java.util.Date}/{@code Calendar}, which move from epoch millis to ISO-8601
 * (a recorded delta pinned by the compatibility-matrix test); {@code Optional} and {@code java.time}
 * go from throwing to working. No inclusion, number, or enum opinion is applied — those belong to
 * {@code vertique}.
 *
 * <p>This type is package-private and registered separately from application-contributed profiles;
 * applications must not contribute a profile with the reserved {@link JsonProfileId#SYSTEM} id, nor
 * with the retired {@code vertx} id this profile was renamed from.
 *
 * <p>It is also the single file in this package that reads Vert.x's raw
 * {@link DatabindCodec#mapper()}: {@link #rawStreamReadConstraints()} exposes that mapper's
 * stream-read limits to {@link JacksonDefaults#applySystem(ObjectMapper)} so every mapper built
 * from the baseline recipe — including one seeded from a fresh {@link ObjectMapper} — carries them.
 */
final class SystemJsonMapperProfile implements JsonMapperProfile {

    /** The reserved id this built-in profile registers under. */
    static final JsonProfileId ID = JsonProfileId.SYSTEM;

    /**
     * The single {@link ObjectMapper} backing this profile, built once at construction from a copy of
     * Vert.x's shared mapper. It is never the shared {@code DatabindCodec.mapper()} instance itself.
     */
    private final ObjectMapper mapper =
            JacksonDefaults.applySystem(DatabindCodec.mapper().copy());

    /**
     * Returns the stream-read limits of Vert.x's raw JSON factory, so the baseline recipe can carry
     * an operator's {@code vertx.jackson.defaultRead*} tuning onto any mapper it configures.
     *
     * @return the raw Vert.x factory's {@link StreamReadConstraints}
     */
    static StreamReadConstraints rawStreamReadConstraints() {
        return DatabindCodec.mapper().getFactory().streamReadConstraints();
    }

    /**
     * {@inheritDoc}
     *
     * @return the reserved {@link JsonProfileId#SYSTEM} id
     */
    @Override
    public JsonProfileId id() {
        return ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the single cached mapper this profile owns — the same instance on every call, so
     * callers may compare it by identity against the process codec's mapper.
     *
     * @return the {@link ObjectMapper} backing the {@code system} profile
     */
    @Override
    public ObjectMapper mapper() {
        return mapper;
    }
}
