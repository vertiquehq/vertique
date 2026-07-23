// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.Module;
import io.vertx.core.json.jackson.VertxModule;

/**
 * Stable, discoverable framework helper that re-exports Vert.x's Jackson {@link VertxModule}.
 *
 * <p>Registering the returned module on a custom {@link com.fasterxml.jackson.databind.ObjectMapper}
 * gives that mapper the same Vert.x JSON type support that {@code DatabindCodec.mapper()} ships with
 * — most importantly serializers/deserializers for {@link io.vertx.core.json.JsonObject} and
 * {@link io.vertx.core.json.JsonArray}, so those types round-trip with their values intact rather
 * than binding to an empty object (FR-JSON-015B).
 *
 * <p>Application-supplied profile mappers (see {@code dev.vertique.core.json.JsonMapperProfile})
 * are expected to register this module so that framework JSON boundaries handling
 * {@code JsonObject}/{@code JsonArray} continue to work regardless of the selected profile. This
 * helper exists so consumers reference a stable framework symbol instead of depending directly on
 * the {@code io.vertx.core.json.jackson.VertxModule} class name.
 */
public final class VertxJsonSupport {

    private VertxJsonSupport() {}

    /**
     * Returns a fresh Vert.x Jackson {@link Module} instance providing serializers and
     * deserializers for the Vert.x JSON types ({@link io.vertx.core.json.JsonObject},
     * {@link io.vertx.core.json.JsonArray}, and related scalar support).
     *
     * @return a new, non-null {@link Module} ready to register on an {@code ObjectMapper}
     */
    public static Module module() {
        return new VertxModule();
    }
}
