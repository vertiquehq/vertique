// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * Typed model of the {@code json} configuration section. Carries the global default JSON mapper
 * profile id. Parsed at the Dagger provider boundary via {@code ConfigParser} (config.md R10).
 *
 * @param jsonProfile the global default profile id (key {@code json.jsonProfile}), or {@code null}
 *     when unset — {@code null}/blank means the reserved {@code vertx} profile (zero-config default).
 */
public record JsonConfig(@Nullable String jsonProfile) {

    /**
     * Jackson factory for the {@code json} section.
     *
     * <p>Passes the configured value through verbatim: a {@code null} stays {@code null} and a blank
     * string stays blank. {@code jsonProfile} is optional, so no compact-constructor validation is
     * needed; the validator and the inline serializer-resolution tail treat a {@code null}/blank id
     * as "no global default" (⇒ the reserved {@code vertx} profile).
     *
     * @param jsonProfile the configured global default profile id, or {@code null} when omitted
     * @return a {@link JsonConfig} carrying the supplied {@code jsonProfile} unchanged
     */
    @JsonCreator
    static JsonConfig fromJson(@JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new JsonConfig(jsonProfile);
    }

    /**
     * The zero-config default: {@code jsonProfile == null} ⇒ the reserved {@code vertx} profile.
     *
     * @return a {@link JsonConfig} with a {@code null} profile id
     */
    public static JsonConfig defaults() {
        return new JsonConfig(null);
    }
}
