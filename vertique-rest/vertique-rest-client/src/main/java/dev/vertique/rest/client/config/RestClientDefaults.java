// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;

/**
 * Typed model of the {@code restClient.defaults} reserved sub-object. Carries the boundary-wide
 * default JSON mapper profile id. Parsed at the Dagger provider boundary via
 * {@link dev.vertique.core.config.ConfigParser} (config rule R10).
 *
 * <p>Mirrors the {@link dev.vertique.json.JsonConfig} pattern: a record with a
 * {@code @JsonCreator} factory and a {@code defaults()} zero-config factory. The typed record
 * replaces the previous hand-rolled {@code JsonObject.getString("jsonProfile")} read so that
 * the canonical {@link dev.vertique.core.config.ConfigParser} governs all scalar reads from the
 * {@code restClient.defaults} section.
 *
 * @param jsonProfile the boundary-wide default profile id ({@code restClient.defaults.jsonProfile}),
 *     or {@code null} when unset — a {@code null}/blank value means no boundary default is
 *     configured and resolution falls through to the global {@code json.jsonProfile} tier or
 *     the reserved {@code vertique} floor, resolved through a
 *     {@link dev.vertique.core.json.JsonMapperProfileRegistry}.
 */
public record RestClientDefaults(@Nullable String jsonProfile) {

    /**
     * Jackson factory for the {@code restClient.defaults} section.
     *
     * <p>Passes the configured value through verbatim: a {@code null} stays {@code null} and a
     * blank string stays blank. {@code jsonProfile} is optional; the builder's precedence chain
     * treats a {@code null}/blank id as "no boundary default".
     *
     * @param jsonProfile the configured boundary-wide default profile id, or {@code null} when omitted
     * @return a {@link RestClientDefaults} carrying the supplied {@code jsonProfile} unchanged
     */
    @JsonCreator
    public static RestClientDefaults fromJson(@JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new RestClientDefaults(jsonProfile);
    }

    /**
     * The zero-config default: {@code jsonProfile == null} means no boundary default is configured.
     *
     * @return a {@link RestClientDefaults} with a {@code null} profile id
     */
    public static RestClientDefaults defaults() {
        return new RestClientDefaults(null);
    }
}
