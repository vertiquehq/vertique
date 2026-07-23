// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.jackson.DatabindCodec;

/**
 * Built-in {@code vertx} profile backed by Vert.x's shared {@link DatabindCodec#mapper()}.
 *
 * <p>This is the zero-config default profile (FR-JSON-006): it is always registered by the
 * framework and exposes the very same {@link ObjectMapper} instance Vert.x uses internally, so a
 * boundary bound to the {@code vertx} profile behaves exactly as the framework does without any
 * profile selection. The mapper is returned <strong>directly</strong> — never copied or wrapped —
 * so callers observe Vert.x's configuration as-is.
 *
 * <p>This type is package-private and registered separately from application-contributed profiles;
 * applications must not contribute a profile with the reserved {@link JsonProfileId#VERTX} id.
 */
final class VertxJsonMapperProfile implements JsonMapperProfile {

    /** The reserved id this built-in profile registers under. */
    static final JsonProfileId ID = JsonProfileId.VERTX;

    /**
     * {@inheritDoc}
     *
     * @return the reserved {@link JsonProfileId#VERTX} id
     */
    @Override
    public JsonProfileId id() {
        return ID;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns Vert.x's shared {@link DatabindCodec#mapper()} instance directly, with no copy
     * (FR-JSON-006).
     *
     * @return the shared Vert.x {@link ObjectMapper}
     */
    @Override
    public ObjectMapper mapper() {
        return DatabindCodec.mapper();
    }
}
