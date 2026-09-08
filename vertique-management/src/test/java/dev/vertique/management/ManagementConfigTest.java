// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ManagementConfig} — verifies default values, full override deserialization,
 * partial override behavior, unknown-property tolerance, and hierarchical config key shape
 * that matches the nested {@code "management"} section in application config files.
 */
class ManagementConfigTest {

    // --- Defaults ---

    @Nested
    class Defaults {

        @Test
        @DisplayName(
                "empty JsonObject deserializes to port=9090, host=0.0.0.0, enabled=true, healthCheckTimeoutSeconds=5")
        void emptyJsonObjectProducesDefaults() {
            ManagementConfig config = new JsonObject().mapTo(ManagementConfig.class);

            assertEquals(9090, config.port());
            assertEquals("0.0.0.0", config.host());
            assertTrue(config.enabled());
            assertEquals(5L, config.healthCheckTimeoutSeconds());
        }
    }

    // --- Overrides ---

    @Nested
    class Overrides {

        @Test
        @DisplayName("all fields set are correctly deserialized")
        void allFieldsDeserialized() {
            JsonObject json = new JsonObject()
                    .put("port", 8081)
                    .put("host", "127.0.0.1")
                    .put("enabled", false)
                    .put("healthCheckTimeoutSeconds", 10);

            ManagementConfig config = json.mapTo(ManagementConfig.class);

            assertEquals(8081, config.port());
            assertEquals("127.0.0.1", config.host());
            assertFalse(config.enabled());
            assertEquals(10L, config.healthCheckTimeoutSeconds());
        }

        @Test
        @DisplayName("partial override of only port keeps other fields at their defaults")
        void partialOverridePortKeepsDefaults() {
            JsonObject json = new JsonObject().put("port", 8080);

            ManagementConfig config = json.mapTo(ManagementConfig.class);

            assertEquals(8080, config.port());
            assertTrue(config.enabled());
            assertEquals(5L, config.healthCheckTimeoutSeconds());
        }

        @Test
        @DisplayName("unknown properties are ignored without error")
        void unknownPropertiesIgnored() {
            JsonObject json = new JsonObject()
                    .put("port", 9090)
                    .put("unknownProperty", "should be ignored")
                    .put("anotherUnknown", 42);

            assertDoesNotThrow(() -> json.mapTo(ManagementConfig.class));
        }
    }

    // --- Hierarchical shape ---

    @Nested
    class HierarchicalShape {

        @Test
        @DisplayName("nested management object deserializes correctly via getJsonObject(\"management\").mapTo()")
        void nestedManagementKeyDeserializesCorrectly() {
            JsonObject appConfig = new JsonObject()
                    .put(
                            "management",
                            new JsonObject()
                                    .put("port", 8080)
                                    .put("enabled", false)
                                    .put("healthCheckTimeoutSeconds", 10));

            ManagementConfig config = appConfig.getJsonObject("management").mapTo(ManagementConfig.class);

            assertEquals(8080, config.port());
            assertFalse(config.enabled());
            assertEquals(10L, config.healthCheckTimeoutSeconds());
        }
    }

    @Nested
    @DisplayName("healthCheckTimeoutSeconds validation (#140)")
    class TimeoutValidation {

        private ManagementConfig parse(JsonObject management) {
            return new DefaultConfigParser(DefaultConfigMapper.lenient()).parse(management, ManagementConfig.class);
        }

        @Test
        @DisplayName("the module provider rejects a non-positive timeout instead of failing at deploy")
        void nonPositiveTimeoutRejectedAtBinding() {
            JsonObject config =
                    new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", 0));

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ManagementModule.managementConfig(
                            config, new DefaultConfigParser(DefaultConfigMapper.lenient())));
            assertTrue(failure.getMessage().contains("healthCheckTimeoutSeconds"), failure.getMessage());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ManagementModule.managementConfig(
                            new JsonObject().put("management", new JsonObject().put("healthCheckTimeoutSeconds", -1)),
                            new DefaultConfigParser(DefaultConfigMapper.lenient())));
        }

        @Test
        @DisplayName("a positive timeout and an absent section both pass")
        void positiveAndAbsentAccepted() {
            DefaultConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

            assertEquals(
                    30L,
                    ManagementModule.managementConfig(
                                    new JsonObject()
                                            .put("management", new JsonObject().put("healthCheckTimeoutSeconds", 30)),
                                    parser)
                            .healthCheckTimeoutSeconds());
            assertEquals(
                    5L,
                    ManagementModule.managementConfig(new JsonObject(), parser).healthCheckTimeoutSeconds());
        }
    }
}
