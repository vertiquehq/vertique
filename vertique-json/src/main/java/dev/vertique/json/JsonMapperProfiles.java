// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import java.util.Objects;

/**
 * Canonical factory for creating {@link JsonMapperProfile} instances from an id and a
 * pre-configured Jackson {@link ObjectMapper}.
 *
 * <p>The factory validates that both arguments are non-null and pairs them into a profile that
 * exposes the given mapper instance <strong>directly</strong> — it never copies, wraps, or mutates
 * the supplied mapper (FR-JSON-009). Whatever configuration the caller installed on the mapper is
 * the configuration the profile exposes.
 */
public final class JsonMapperProfiles {

    private JsonMapperProfiles() {}

    /**
     * Creates a {@link JsonMapperProfile} pairing {@code id} with {@code mapper}.
     *
     * <p>The returned profile's {@link JsonMapperProfile#mapper()} returns the exact
     * {@code ObjectMapper} instance passed in — no defensive copy is made, and the mapper is not
     * mutated (FR-JSON-009).
     *
     * @param id the non-null profile id the returned profile reports from {@link JsonMapperProfile#id()}
     * @param mapper the non-null, pre-configured mapper the returned profile exposes as-is
     * @return a non-null {@link JsonMapperProfile} backed by the given id and mapper
     * @throws NullPointerException if {@code id} or {@code mapper} is {@code null}
     */
    public static JsonMapperProfile of(JsonProfileId id, ObjectMapper mapper) {
        Objects.requireNonNull(id, "JsonMapperProfile id must not be null");
        Objects.requireNonNull(mapper, "JsonMapperProfile mapper must not be null");
        return new RecordedProfile(id, mapper);
    }

    /**
     * Immutable {@link JsonMapperProfile} that returns its id and mapper exactly as supplied to
     * {@link #of(JsonProfileId, ObjectMapper)}, without any copy or mutation.
     *
     * @param id the profile id
     * @param mapper the mapper instance, exposed as-is
     */
    private record RecordedProfile(JsonProfileId id, ObjectMapper mapper) implements JsonMapperProfile {}
}
