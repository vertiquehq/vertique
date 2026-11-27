// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the built-in {@link SystemJsonProfileCodec}, verifying that its id is the reserved
 * {@code system} id and that its mapper is a cached <em>copy</em> of Vert.x's shared
 * {@link DatabindCodec#mapper()} — never the shared instance itself, and the same instance on every
 * call.
 */
class SystemJsonProfileCodecTest {

    @Test
    @DisplayName("id() is the reserved system profile id")
    void id_isSystem() {
        assertEquals(JsonProfileId.SYSTEM, new SystemJsonProfileCodec().id());
    }

    @Test
    @DisplayName("mapper() returns a copy of DatabindCodec.mapper(), never the shared instance")
    void mapper_returnsCopy_notTheSharedInstance() {
        assertNotSame(DatabindCodec.mapper(), new SystemJsonProfileCodec().mapper());
    }

    @Test
    @DisplayName("mapper() returns the same cached instance on every call")
    void mapper_isStableAcrossCalls() {
        SystemJsonProfileCodec profile = new SystemJsonProfileCodec();

        assertSame(Jackson2JsonProfileCodec.objectMapper(profile), Jackson2JsonProfileCodec.objectMapper(profile));
    }

    @Test
    @DisplayName("every mapper built from the system recipe rejects text after the JSON value")
    void recipe_rejectsTrailingTokens() throws Exception {
        ObjectMapper system = new SystemJsonProfileCodec().mapper();
        ObjectMapper seeded = JacksonDefaults.applySystem(new ObjectMapper());
        ObjectMapper vertique = JacksonDefaults.apply(new ObjectMapper());

        for (ObjectMapper mapper : new ObjectMapper[] {system, seeded, vertique}) {
            assertTrue(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
            assertThrows(JsonProcessingException.class, () -> mapper.readTree("{\"a\":1} junk"));
            assertThrows(JsonProcessingException.class, () -> mapper.readValue("{\"a\":1} {\"b\":2}", Object.class));
            assertEquals(1, mapper.readTree("{\"a\":1}  \n").get("a").asInt());
            JsonNode scalar = mapper.readTree("42");
            assertEquals(42, scalar.asInt());
        }
    }

    @Test
    @DisplayName("Vert.x's own parsing already rejected text after the value, so the process codec is unchanged")
    void vertxParsing_rejectsTrailingTokens() {
        assertThrows(DecodeException.class, () -> new JsonObject("{\"a\":1} junk"));
    }
}
