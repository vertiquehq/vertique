// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;

/**
 * Built-in {@code vertique} profile exposing the framework's opinionated JSON defaults.
 *
 * <p>This is the reserved opinionated default profile: it is always registered by the framework
 * alongside the {@code vertx} profile, under the reserved id {@code vertique}. Unlike the
 * {@code vertx} profile (which returns Vert.x's shared mapper directly), the {@code vertique}
 * profile owns its own {@link ObjectMapper} configured with the broadly-safe defaults from
 * {@link JacksonDefaults} — so it never mutates {@code DatabindCodec.mapper()}.
 *
 * <p>This type is package-private and registered separately from application-contributed profiles;
 * applications must not contribute a profile with the reserved {@code vertique} id (FR-JSON-044).
 */
final class VertiqueJsonMapperProfile implements JsonMapperProfile {

    /** The reserved id this built-in profile registers under. */
    static final JsonProfileId ID = JsonProfileId.of("vertique");

    /**
     * The single independent {@link ObjectMapper} backing this profile, built once at construction by
     * applying the {@link JacksonDefaults} opinionated defaults to a fresh mapper. It is never the
     * shared Vert.x {@code DatabindCodec.mapper()} (FR-JSON-045).
     */
    private final ObjectMapper mapper = JacksonDefaults.apply(new ObjectMapper());

    /**
     * {@inheritDoc}
     *
     * @return the reserved {@code vertique} id
     */
    @Override
    public JsonProfileId id() {
        return ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the single independent {@link ObjectMapper} this profile owns — built once via
     * {@code JacksonDefaults.apply(new ObjectMapper())} and returned as-is, never copied or wrapped.
     * This mapper is the profile's own instance, so the {@code vertique} profile never mutates Vert.x's
     * shared {@code DatabindCodec.mapper()} (FR-JSON-045).
     *
     * @return the {@link ObjectMapper} backing the {@code vertique} profile
     */
    @Override
    public ObjectMapper mapper() {
        return mapper;
    }
}
