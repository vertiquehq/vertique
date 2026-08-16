// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Canonical factory for creating {@link JsonMapperProfile} instances from an id and a
 * pre-configured Jackson {@link ObjectMapper}, optionally declaring JSON Schema type overrides.
 *
 * <p>The factory validates that its arguments are non-null and pairs them into a profile that
 * exposes the given mapper instance <strong>directly</strong> — it never copies, wraps, or mutates
 * the supplied mapper (FR-JSON-009). Whatever configuration the caller installed on the mapper is
 * the configuration the profile exposes.
 */
public final class JsonMapperProfiles {

    private JsonMapperProfiles() {}

    /**
     * Creates a {@link JsonMapperProfile} pairing {@code id} with {@code mapper} and declaring no
     * JSON Schema type overrides.
     *
     * <p>The returned profile's {@link JsonMapperProfile#mapper()} returns the exact
     * {@code ObjectMapper} instance passed in — no defensive copy is made, and the mapper is not
     * mutated (FR-JSON-009). This is the pre-amendment factory shape; its behavior is unchanged by
     * the addition of {@link #of(JsonProfileId, ObjectMapper, Collection)} (FR-JSON-088).
     *
     * @param id the non-null profile id the returned profile reports from {@link JsonMapperProfile#id()}
     * @param mapper the non-null, pre-configured mapper the returned profile exposes as-is
     * @return a non-null {@link JsonMapperProfile} backed by the given id and mapper, declaring no
     *     schema overrides
     * @throws NullPointerException if {@code id} or {@code mapper} is {@code null}
     */
    public static JsonMapperProfile of(JsonProfileId id, ObjectMapper mapper) {
        Objects.requireNonNull(id, "JsonMapperProfile id must not be null");
        Objects.requireNonNull(mapper, "JsonMapperProfile mapper must not be null");
        return new RecordedProfile(id, mapper, List.of());
    }

    /**
     * Creates a {@link JsonMapperProfile} pairing {@code id} with {@code mapper}, additionally
     * declaring {@code overrides} as the profile's JSON Schema type overrides (FR-JSON-088).
     *
     * <p>{@code overrides} is defensively copied into an immutable list: the caller's collection may
     * be freely mutated after this call returns without affecting the returned profile, and the
     * returned profile's {@link JsonMapperProfile#jsonSchemaTypeOverrides()} is unmodifiable and
     * returns the same list instance on every call.
     *
     * @param id the non-null profile id the returned profile reports from {@link JsonMapperProfile#id()}
     * @param mapper the non-null, pre-configured mapper the returned profile exposes as-is
     * @param overrides the non-null collection of overrides to declare; must not contain a
     *     {@code null} element
     * @return a non-null {@link JsonMapperProfile} backed by the given id and mapper, declaring an
     *     immutable copy of {@code overrides}
     * @throws NullPointerException if {@code id}, {@code mapper}, {@code overrides}, or any element
     *     of {@code overrides} is {@code null}
     */
    public static JsonMapperProfile of(
            JsonProfileId id, ObjectMapper mapper, Collection<JsonSchemaTypeOverride> overrides) {
        Objects.requireNonNull(id, "JsonMapperProfile id must not be null");
        Objects.requireNonNull(mapper, "JsonMapperProfile mapper must not be null");
        Objects.requireNonNull(overrides, "JsonMapperProfile overrides must not be null");
        List<JsonSchemaTypeOverride> copy = List.copyOf(overrides);
        return new RecordedProfile(id, mapper, copy);
    }

    /**
     * Immutable {@link JsonMapperProfile} that returns its id and mapper exactly as supplied, without
     * any copy or mutation, and exposes the given, already-immutable schema override list as-is.
     *
     * @param id the profile id
     * @param mapper the mapper instance, exposed as-is
     * @param overrides the declared JSON Schema type overrides; already an unmodifiable list
     */
    private record RecordedProfile(JsonProfileId id, ObjectMapper mapper, List<JsonSchemaTypeOverride> overrides)
            implements JsonMapperProfile {

        @Override
        public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
            return overrides;
        }
    }
}
