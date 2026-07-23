// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.json.JsonProfileConfigurationException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link dev.vertique.core.json.JsonMapperProfileRegistry#validateConfigured(String)}
 * as implemented by {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>Uses a real {@link DefaultJsonMapperProfileRegistry} seeded with no application profiles
 * (the built-in {@code vertx} and {@code vertique} profiles are always present). Verifies:
 *
 * <ul>
 *   <li>{@code null} is a no-op (does not throw);
 *   <li>a blank string is a no-op (does not throw);
 *   <li>a known built-in id ({@code vertx}) is resolved cleanly (does not throw);
 *   <li>a known built-in id ({@code vertique}) is resolved cleanly (does not throw);
 *   <li>an unknown non-blank id throws {@link JsonProfileConfigurationException}.
 * </ul>
 */
@DisplayName("JsonMapperProfileRegistry.validateConfigured")
class JsonMapperProfileRegistryValidateConfiguredTest {

    private DefaultJsonMapperProfileRegistry registry;

    @BeforeEach
    void setUp() {
        // Seed with no application profiles — built-ins (vertx, vertique) are seeded automatically.
        registry = new DefaultJsonMapperProfileRegistry(Set.of());
    }

    @Test
    @DisplayName("null profileId is a no-op")
    void nullProfileId_isNoOp() {
        assertDoesNotThrow(() -> registry.validateConfigured(null), "null must not trigger a registry lookup");
    }

    @Test
    @DisplayName("blank profileId is a no-op")
    void blankProfileId_isNoOp() {
        assertDoesNotThrow(() -> registry.validateConfigured("  "), "blank must not trigger a registry lookup");
    }

    @Test
    @DisplayName("empty string profileId is a no-op")
    void emptyProfileId_isNoOp() {
        assertDoesNotThrow(() -> registry.validateConfigured(""), "empty string must not trigger a registry lookup");
    }

    @Test
    @DisplayName("known vertx id passes")
    void knownVertxId_passes() {
        assertDoesNotThrow(() -> registry.validateConfigured("vertx"), "the reserved 'vertx' id must resolve cleanly");
    }

    @Test
    @DisplayName("known vertique id passes")
    void knownVertiqueId_passes() {
        assertDoesNotThrow(
                () -> registry.validateConfigured("vertique"), "the built-in 'vertique' id must resolve cleanly");
    }

    @Test
    @DisplayName("unknown non-blank id throws JsonProfileConfigurationException")
    void unknownId_throwsJsonProfileConfigurationException() {
        assertThrows(
                JsonProfileConfigurationException.class,
                () -> registry.validateConfigured("no-such-profile"),
                "an unknown non-blank id must throw JsonProfileConfigurationException");
    }
}
