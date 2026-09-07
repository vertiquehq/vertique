// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.JsonProfileId;
import jakarta.annotation.Nullable;

/**
 * Typed model of the {@code json} configuration section. Carries the two framework-wide profile
 * selections. Parsed at the Dagger provider boundary via {@code ConfigParser} (config.md R10).
 *
 * <p>Both components are stored verbatim — {@code null} stays {@code null} and a blank string stays
 * blank. The floors live in the accessors {@link #effectiveProfile()} and
 * {@link #effectiveSystemProfile()}, which never return {@code null} and never validate: an id that
 * names no registered profile is rejected by {@link JsonDefaultProfileValidator} at the
 * {@code VALIDATE} phase, not here.
 *
 * @param jsonProfile the default profile id for managed edges (key {@code json.jsonProfile}), or
 *     {@code null}/blank when unset — the floor is the reserved {@code vertique} id
 * @param systemProfile the profile id installed as the process JSON codec (key
 *     {@code json.systemProfile}), or {@code null}/blank when unset — the floor is the reserved
 *     {@link JsonProfileId#SYSTEM} id
 */
public record JsonConfig(
        @Nullable String jsonProfile, @Nullable String systemProfile) {

    /**
     * Convenience constructor: {@code this(jsonProfile, null)}.
     *
     * @param jsonProfile the default profile id for managed edges, or {@code null} when unset
     */
    public JsonConfig(@Nullable String jsonProfile) {
        this(jsonProfile, null);
    }

    /**
     * Jackson factory for the {@code json} section.
     *
     * <p>Passes both configured values through verbatim: a {@code null} stays {@code null} and a blank
     * string stays blank. Neither key is required, so no compact-constructor validation is needed; the
     * accessors below apply the floors.
     *
     * @param jsonProfile the configured managed-edge default profile id, or {@code null} when omitted
     * @param systemProfile the configured process-codec profile id, or {@code null} when omitted
     * @return a {@link JsonConfig} carrying both values unchanged
     */
    @JsonCreator
    static JsonConfig fromJson(
            @JsonProperty("jsonProfile") @Nullable String jsonProfile,
            @JsonProperty("systemProfile") @Nullable String systemProfile) {
        return new JsonConfig(jsonProfile, systemProfile);
    }

    /**
     * The zero-config default: both keys unset, so the floors apply ({@code vertique} for managed
     * edges, {@code system} for the process codec).
     *
     * @return a {@link JsonConfig} with both profile ids {@code null}
     */
    public static JsonConfig defaults() {
        return new JsonConfig(null, null);
    }

    /**
     * The default profile for managed edges (REST bodies, rest-client, Kafka, MCP):
     * {@link #jsonProfile()} when non-blank, else the reserved {@code vertique} id.
     *
     * <p>The value is returned verbatim, unvalidated — resolving it through the registry is the
     * caller's (or the validator's) job.
     *
     * @return the effective managed-edge profile id; never {@code null}
     */
    public JsonProfileId effectiveProfile() {
        return isBlank(jsonProfile) ? VertiqueJsonMapperProfile.ID : JsonProfileId.of(jsonProfile);
    }

    /**
     * The profile installed as the process JSON codec: {@link #systemProfile()} when non-blank, else
     * the reserved {@link JsonProfileId#SYSTEM} id.
     *
     * <p>The value is returned verbatim, unvalidated — resolving it through the registry is the
     * install step's job.
     *
     * @return the effective process-codec profile id; never {@code null}
     */
    public JsonProfileId effectiveSystemProfile() {
        return isBlank(systemProfile) ? JsonProfileId.SYSTEM : JsonProfileId.of(systemProfile);
    }

    /**
     * Returns whether a configured id is absent for floor purposes.
     *
     * @param value the raw configured value
     * @return {@code true} when {@code value} is {@code null} or contains only whitespace
     */
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
