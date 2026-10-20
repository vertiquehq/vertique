// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ConfigurationException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Unit tests for binding {@code db.schemas} into {@link DbPoolConfig#schemas()}. */
@DisplayName("DbPoolConfig schemas binding")
class DbPoolConfigSchemasTest {

    /** A value that must never appear in a configuration error raised by the placement itself. */
    private static final String SENTINEL = "sentinel-schema-value-7f3a";

    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    @DisplayName("binds the schemas object of the db section")
    Stream<DynamicTest> acceptedShapes() {
        return Stream.of(
                accepts("no schemas setting", "{\"host\":\"db.example\"}", Map.of()),
                accepts("schemas is JSON null", "{\"schemas\":null}", Map.of()),
                accepts("schemas is an empty object", "{\"schemas\":{}}", Map.of()),
                accepts(
                        "valid map including a key that matches no migration instance",
                        "{\"schemas\":{\"job\":\"jobs\",\"app\":\"app_schema\"}}",
                        Map.of("job", "jobs", "app", "app_schema")),
                accepts(
                        "value is kept verbatim because the binding applies no vendor rules",
                        "{\"schemas\":{\"job\":\"Mixed-Case.Schema 1\"}}",
                        Map.of("job", "Mixed-Case.Schema 1")));
    }

    @TestFactory
    @DisplayName("rejects a malformed schemas setting")
    Stream<DynamicTest> rejectedShapes() {
        return Stream.of(
                rejectsNamingSetting("value is a number", "{\"schemas\":{\"job\":42}}", "db.schemas.job"),
                rejectsNamingSetting("value is a boolean", "{\"schemas\":{\"job\":true}}", "db.schemas.job"),
                rejectsNamingSetting(
                        "value is an object",
                        "{\"schemas\":{\"job\":{\"name\":\"" + SENTINEL + "\"}}}",
                        "db.schemas.job"),
                rejectsNamingSetting(
                        "value is an array", "{\"schemas\":{\"job\":[\"" + SENTINEL + "\"]}}", "db.schemas.job"),
                rejectsNamingSetting("value is JSON null", "{\"schemas\":{\"job\":null}}", "db.schemas.job"),
                rejectsNamingSetting("value is empty", "{\"schemas\":{\"job\":\"\"}}", "db.schemas.job"),
                rejectsNamingSetting("value is blank", "{\"schemas\":{\"job\":\"   \"}}", "db.schemas.job"),
                rejectsNamingSetting(
                        "malformed value next to a valid one names the offending key",
                        "{\"schemas\":{\"app\":\"app_schema\",\"job\":42}}",
                        "db.schemas.job"),
                rejectsNamingSetting("key is empty", "{\"schemas\":{\"\":\"" + SENTINEL + "\"}}", "db.schemas"),
                rejectsNamingSetting("key is blank", "{\"schemas\":{\"  \":\"" + SENTINEL + "\"}}", "db.schemas"),
                rejects("schemas is a string", "{\"schemas\":\"" + SENTINEL + "\"}"),
                rejects("schemas is an array", "{\"schemas\":[\"" + SENTINEL + "\"]}"),
                rejects("schemas is a number", "{\"schemas\":7}"));
    }

    @Test
    @DisplayName("schemas serializes as the plain placement object")
    void serializesAsPlainObject() throws Exception {
        DbPoolConfig config = DbPoolConfig.builder()
                .schemas(new DbSchemas(Map.of("job", "jobs")))
                .build();

        assertEquals(
                Map.of("job", "jobs"),
                mapper.convertValue(
                        mapper.readTree(mapper.writeValueAsString(config)).get("schemas"), Map.class));
    }

    @Test
    @DisplayName("a programmatic null placement behaves as the empty placement")
    void programmaticNullIsEmpty() {
        DbPoolConfig config = DbPoolConfig.builder().schemas(null).build();

        assertNotNull(config.schemas());
        assertEquals(Map.of(), config.schemas().mappings());
        assertTrue(config.toString().contains("schemas={}"));
    }

    private DynamicTest accepts(String shape, String json, Map<String, String> expected) {
        return DynamicTest.dynamicTest("accepts: " + shape, () -> {
            // Given: the db section in this shape; When: it is bound
            DbSchemas schemas = mapper.readValue(json, DbPoolConfig.class).schemas();

            // Then: exactly the expected mappings are bound
            assertEquals(expected, schemas.mappings());
            expected.forEach((name, schema) -> assertEquals(Optional.of(schema), schemas.schema(name)));
            assertEquals(Optional.empty(), schemas.schema("unmapped-name"));
        });
    }

    private DynamicTest rejects(String shape, String json) {
        return DynamicTest.dynamicTest(
                "rejects: " + shape,
                () -> assertThrows(Exception.class, () -> mapper.readValue(json, DbPoolConfig.class)));
    }

    private DynamicTest rejectsNamingSetting(String shape, String json, String namedSetting) {
        return DynamicTest.dynamicTest("rejects: " + shape, () -> {
            // Given: the db section in this malformed shape; When: it is bound
            Exception failure = assertThrows(Exception.class, () -> mapper.readValue(json, DbPoolConfig.class));

            // Then: a configuration error in the cause chain names the setting and never echoes a value
            ConfigurationException config = null;
            for (Throwable t = failure; t != null && config == null; t = t.getCause()) {
                if (t instanceof ConfigurationException found) {
                    config = found;
                }
            }
            assertNotNull(config, () -> "no ConfigurationException in " + failure);
            ConfigurationException named = config;
            assertTrue(
                    named.getMessage().contains(namedSetting),
                    () -> "message should name " + namedSetting + " but was: " + named.getMessage());
            assertFalse(named.getMessage().contains(SENTINEL));
        });
    }
}
