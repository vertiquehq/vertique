// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link JsonMapperProfiles} factory, verifying that {@code of(...)} pairs the
 * given id with the given mapper, never mutates or copies the mapper (FR-JSON-009), and rejects
 * null arguments.
 */
class JsonMapperProfilesTest {

    @Test
    @DisplayName("of(id, mapper) returns a profile exposing the given id and mapper")
    void of_returnsProfileWithIdAndMapper() {
        JsonProfileId id = JsonProfileId.of("payments");
        ObjectMapper mapper = new ObjectMapper();

        JsonMapperProfile profile = JsonMapperProfiles.of(id, mapper);

        assertEquals(id, profile.id());
        assertSame(mapper, profile.mapper());
    }

    @Test
    @DisplayName("of(id, mapper) does not copy the mapper — profile.mapper() is the same instance")
    void of_doesNotMutateMapper() {
        ObjectMapper mapper = new ObjectMapper();

        JsonMapperProfile profile = JsonMapperProfiles.of(JsonProfileId.of("payments"), mapper);

        assertSame(mapper, profile.mapper());
    }

    @Test
    @DisplayName("of(id, mapper) rejects a null id")
    void of_rejectsNullId() {
        assertThrows(Exception.class, () -> JsonMapperProfiles.of(null, new ObjectMapper()));
    }

    @Test
    @DisplayName("of(id, mapper) rejects a null mapper")
    void of_rejectsNullMapper() {
        assertThrows(Exception.class, () -> JsonMapperProfiles.of(JsonProfileId.of("payments"), null));
    }
}
