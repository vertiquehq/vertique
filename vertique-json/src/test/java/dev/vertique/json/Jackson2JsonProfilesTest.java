// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Jackson2JsonProfiles} factory, verifying that {@code of(...)} pairs the
 * given id with the given mapper, never mutates or copies the mapper, and rejects
 * null arguments.
 */
class Jackson2JsonProfilesTest {

    @Test
    @DisplayName("of(id, mapper) returns a profile exposing the given id and mapper")
    void of_returnsProfileWithIdAndMapper() {
        JsonProfileId id = JsonProfileId.of("payments");
        ObjectMapper mapper = new ObjectMapper();

        JsonProfileCodec profile = Jackson2JsonProfiles.of(id, mapper);

        assertEquals(id, profile.id());
        assertSame(mapper, Jackson2JsonProfileCodec.objectMapper(profile));
    }

    @Test
    @DisplayName(
            "of(id, mapper) does not copy the mapper — Jackson2JsonProfileCodec.objectMapper(profile) is the same instance")
    void of_doesNotMutateMapper() {
        ObjectMapper mapper = new ObjectMapper();

        JsonProfileCodec profile = Jackson2JsonProfiles.of(JsonProfileId.of("payments"), mapper);

        assertSame(mapper, Jackson2JsonProfileCodec.objectMapper(profile));
    }

    @Test
    @DisplayName("of(id, mapper) rejects a null id")
    void of_rejectsNullId() {
        assertThrows(Exception.class, () -> Jackson2JsonProfiles.of(null, new ObjectMapper()));
    }

    @Test
    @DisplayName("of(id, mapper) rejects a null mapper")
    void of_rejectsNullMapper() {
        assertThrows(Exception.class, () -> Jackson2JsonProfiles.of(JsonProfileId.of("payments"), null));
    }
}
