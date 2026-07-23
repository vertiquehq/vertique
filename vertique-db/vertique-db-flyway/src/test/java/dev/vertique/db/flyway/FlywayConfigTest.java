// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link FlywayConfig} builder defaults and Jackson deserialization work correctly
 * for all fields, including the new {@code locations} (List), {@code schemas}, {@code placeholders},
 * {@code target}, {@code outOfOrder}, and {@code cleanDisabled} fields.
 */
class FlywayConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- Builder defaults ---

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("should have correct defaults for all fields")
        void shouldHaveCorrectDefaults() {
            FlywayConfig config = FlywayConfig.builder().build();
            assertEquals(FlywayMode.MIGRATE, config.mode());
            assertNull(config.jdbcUrl());
            assertNull(config.user());
            assertNull(config.password());
            assertEquals(List.of("classpath:db/migration"), config.locations());
            assertNull(config.target());
            assertEquals(List.of(), config.schemas());
            assertEquals(Map.of(), config.placeholders());
            assertFalse(config.outOfOrder());
            assertTrue(config.cleanDisabled());
            assertFalse(config.baselineOnMigrate());
            assertEquals("1", config.baselineVersion());
            assertTrue(config.validateOnMigrate());
        }
    }

    // --- Deserialization ---

    @Nested
    @DisplayName("JSON deserialization")
    class Deserialization {

        @Test
        @DisplayName("should deserialize full config")
        void shouldDeserializeFullConfig() throws Exception {
            String json = """
                    {
                      "mode": "VALIDATE",
                      "jdbcUrl": "jdbc:postgresql://localhost:5432/mydb",
                      "user": "ddl_user",
                      "password": "ddl_secret",
                      "locations": ["classpath:db/migration", "classpath:db/extra"],
                      "baselineOnMigrate": true,
                      "baselineVersion": "2",
                      "validateOnMigrate": false
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(FlywayMode.VALIDATE, config.mode());
            assertEquals("jdbc:postgresql://localhost:5432/mydb", config.jdbcUrl());
            assertEquals("ddl_user", config.user());
            assertEquals("ddl_secret", config.password());
            assertEquals(List.of("classpath:db/migration", "classpath:db/extra"), config.locations());
            assertTrue(config.baselineOnMigrate());
            assertEquals("2", config.baselineVersion());
            assertFalse(config.validateOnMigrate());
        }

        @Test
        @DisplayName("should deserialize locations as JSON array")
        void shouldDeserializeLocationsAsArray() throws Exception {
            String json = """
                    {
                      "locations": ["classpath:db/migration", "filesystem:/opt/sql"]
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(List.of("classpath:db/migration", "filesystem:/opt/sql"), config.locations());
        }

        @Test
        @DisplayName("should deserialize schemas as JSON array")
        void shouldDeserializeSchemasAsArray() throws Exception {
            String json = """
                    {
                      "schemas": ["public", "audit"]
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(List.of("public", "audit"), config.schemas());
        }

        @Test
        @DisplayName("should deserialize placeholders as JSON object")
        void shouldDeserializePlaceholders() throws Exception {
            String json = """
                    {
                      "placeholders": { "schema": "public", "env": "test" }
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(Map.of("schema", "public", "env", "test"), config.placeholders());
        }

        @Test
        @DisplayName("should deserialize target version")
        void shouldDeserializeTarget() throws Exception {
            String json = """
                    {
                      "target": "3"
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals("3", config.target());
        }

        @Test
        @DisplayName("should deserialize outOfOrder and cleanDisabled flags")
        void shouldDeserializeOutOfOrderAndCleanDisabled() throws Exception {
            String json = """
                    {
                      "outOfOrder": true,
                      "cleanDisabled": false
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertTrue(config.outOfOrder());
            assertFalse(config.cleanDisabled());
        }

        @Test
        @DisplayName("should deserialize DISABLED mode")
        void shouldDeserializeDisabledMode() throws Exception {
            String json = """
                    {
                      "mode": "DISABLED"
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(FlywayMode.DISABLED, config.mode());
        }

        @Test
        @DisplayName("should ignore unknown properties")
        void shouldIgnoreUnknownProperties() throws Exception {
            String json = """
                    {
                      "mode": "MIGRATE",
                      "unknownField": "value"
                    }
                    """;

            FlywayConfig config = mapper.readValue(json, FlywayConfig.class);
            assertEquals(FlywayMode.MIGRATE, config.mode());
        }
    }
}
