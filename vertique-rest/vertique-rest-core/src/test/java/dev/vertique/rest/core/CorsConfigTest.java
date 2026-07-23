// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.config.CorsConfig;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CorsConfig} — deserialization and default values.
 */
class CorsConfigTest {

    // --- Default values ---

    @Test
    @DisplayName("Should have CORS disabled by default")
    void shouldBeDisabledByDefault() {
        CorsConfig config = CorsConfig.builder().build();

        assertFalse(config.enabled());
    }

    @Test
    @DisplayName("Should default to wildcard origin")
    void shouldDefaultToWildcardOrigin() {
        CorsConfig config = CorsConfig.builder().build();

        assertEquals(List.of("*"), config.origins());
    }

    @Test
    @DisplayName("Should default to standard HTTP methods")
    void shouldDefaultToStandardMethods() {
        CorsConfig config = CorsConfig.builder().build();

        Set<String> methods = config.allowedMethods();
        assertTrue(methods.containsAll(Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")));
    }

    @Test
    @DisplayName("Should default to wildcard allowed headers")
    void shouldDefaultToWildcardAllowedHeaders() {
        CorsConfig config = CorsConfig.builder().build();

        assertEquals(Set.of("*"), config.allowedHeaders());
    }

    @Test
    @DisplayName("Should default to empty exposed headers")
    void shouldDefaultToEmptyExposedHeaders() {
        CorsConfig config = CorsConfig.builder().build();

        assertTrue(config.exposedHeaders().isEmpty());
    }

    @Test
    @DisplayName("Should default allowCredentials to false")
    void shouldDefaultAllowCredentialsToFalse() {
        CorsConfig config = CorsConfig.builder().build();

        assertFalse(config.allowCredentials());
    }

    @Test
    @DisplayName("Should default maxAge to 3600")
    void shouldDefaultMaxAgeTo3600() {
        CorsConfig config = CorsConfig.builder().build();

        assertEquals(3600, config.maxAge());
    }

    // --- Deserialization via JsonObject.mapTo ---

    @Test
    @DisplayName("Should deserialize enabled flag from JSON")
    void shouldDeserializeEnabledFlag() {
        JsonObject json = new JsonObject().put("enabled", true);

        CorsConfig config = json.mapTo(CorsConfig.class);

        assertTrue(config.enabled());
    }

    @Test
    @DisplayName("Should deserialize origins list from JSON")
    void shouldDeserializeOrigins() {
        JsonObject json = new JsonObject()
                .put("enabled", true)
                .put(
                        "origins",
                        new io.vertx.core.json.JsonArray()
                                .add("https://example.com")
                                .add("https://other.com"));

        CorsConfig config = json.mapTo(CorsConfig.class);

        assertEquals(List.of("https://example.com", "https://other.com"), config.origins());
    }

    @Test
    @DisplayName("Should deserialize allowCredentials from JSON")
    void shouldDeserializeAllowCredentials() {
        JsonObject json = new JsonObject().put("allowCredentials", true);

        CorsConfig config = json.mapTo(CorsConfig.class);

        assertTrue(config.allowCredentials());
    }

    @Test
    @DisplayName("Should deserialize maxAge from JSON")
    void shouldDeserializeMaxAge() {
        JsonObject json = new JsonObject().put("maxAge", 600);

        CorsConfig config = json.mapTo(CorsConfig.class);

        assertEquals(600, config.maxAge());
    }

    @Test
    @DisplayName("Should ignore unknown JSON fields")
    void shouldIgnoreUnknownFields() {
        JsonObject json = new JsonObject().put("enabled", true).put("unknownField", "ignored");

        assertDoesNotThrow(() -> json.mapTo(CorsConfig.class));
    }

    @Test
    @DisplayName("Should use defaults for fields absent from JSON")
    void shouldUseDefaultsForAbsentFields() {
        JsonObject json = new JsonObject().put("enabled", true);

        CorsConfig config = json.mapTo(CorsConfig.class);

        assertTrue(config.enabled());
        assertEquals(List.of("*"), config.origins());
        assertEquals(3600, config.maxAge());
        assertFalse(config.allowCredentials());
    }

    @Test
    @DisplayName("Should deserialize via Jackson ObjectMapper")
    void shouldDeserializeViaJackson() throws Exception {
        String json = "{\"enabled\":true,\"origins\":[\"https://app.example.com\"],\"maxAge\":1800}";

        CorsConfig config = new ObjectMapper().readValue(json, CorsConfig.class);

        assertTrue(config.enabled());
        assertEquals(List.of("https://app.example.com"), config.origins());
        assertEquals(1800, config.maxAge());
    }
}
