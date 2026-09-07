// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;

/**
 * Built-in {@code vertique} profile exposing the framework's opinionated JSON defaults.
 *
 * <p>This is the reserved opinionated default profile — the default for managed edges: it is always
 * registered by the framework alongside the {@code system} profile, under the reserved id
 * {@code vertique}. It owns its own {@link ObjectMapper}, seeded from a <strong>fresh</strong>
 * {@code ObjectMapper} (not from the {@code system} profile's copy of Vert.x's mapper) and
 * configured with {@link JacksonDefaults#apply(ObjectMapper)} — the baseline recipe plus the
 * framework's opinions. Seeding fresh is deliberate: it keeps Vert.x's {@code ALLOW_COMMENTS}
 * leniency <em>off</em> at the untrusted-input boundary, so this profile still rejects JSON
 * comments. It never mutates Vert.x's shared mapper.
 *
 * <p>This type is package-private and registered separately from application-contributed profiles;
 * applications must not contribute a profile with the reserved {@code vertique} id (FR-JSON-044).
 */
final class VertiqueJsonMapperProfile implements JsonMapperProfile {

    /** The reserved id this built-in profile registers under. */
    static final JsonProfileId ID = JsonProfileId.of("vertique");

    /**
     * The single independent {@link ObjectMapper} backing this profile, built once at construction by
     * applying the {@link JacksonDefaults} baseline recipe and opinions to a fresh mapper. It is never
     * the shared Vert.x {@code DatabindCodec.mapper()} (FR-JSON-045).
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
     * {@code JacksonDefaults.apply(new ObjectMapper())} and returned as-is (the same instance on every
     * call), never copied or wrapped. This mapper is the profile's own instance, so the
     * {@code vertique} profile never mutates Vert.x's shared mapper (FR-JSON-045).
     *
     * @return the {@link ObjectMapper} backing the {@code vertique} profile
     */
    @Override
    public ObjectMapper mapper() {
        return mapper;
    }
}
