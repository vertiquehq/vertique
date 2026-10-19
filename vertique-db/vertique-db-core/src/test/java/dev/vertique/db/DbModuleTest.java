// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link DbModule#dbPoolConfig}: the provider returns the parsed config and logs
 * every {@link DbPoolConfig#validate()} warning at WARN without failing.
 */
@DisplayName("DbModule pool-config provider")
class DbModuleTest {

    private final JsonObject appConfig = new JsonObject().put("db", new JsonObject().put("host", "ignored"));
    private final ConfigParser parser = mock(ConfigParser.class);
    private final Logger moduleLogger = (Logger) LoggerFactory.getLogger(DbModule.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        moduleLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        moduleLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("logs each validation warning at WARN and still returns the config")
    void warnings_loggedAndConfigReturned() {
        // Given: no host, non-positive pool size, and a trust store under sslMode DISABLE — three warnings
        DbPoolConfig misconfigured = DbPoolConfig.builder()
                .maxPoolSize(0)
                .trustStorePath("/etc/certs/db-ca.pem")
                .build();
        when(parser.parse(any(JsonObject.class), eq(DbPoolConfig.class))).thenReturn(misconfigured);

        // When
        DbPoolConfig provided = DbModule.dbPoolConfig(appConfig, parser);

        // Then
        assertSame(misconfigured, provided);
        List<String> warnings = warnMessages();
        assertEquals(misconfigured.validate().size(), warnings.size(), "one WARN line per validation warning");
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.host is not configured")));
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.maxPoolSize must be positive")));
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.trustStorePath is set but sslMode is DISABLE")));
    }

    @Test
    @DisplayName("a valid config logs nothing")
    void validConfig_logsNothing() {
        DbPoolConfig valid = DbPoolConfig.builder().host("db.example").build();
        when(parser.parse(any(JsonObject.class), eq(DbPoolConfig.class))).thenReturn(valid);

        assertSame(valid, DbModule.dbPoolConfig(appConfig, parser));
        assertEquals(List.of(), warnMessages());
    }

    // -- db.schemas binding --

    /** A value that must never appear in a configuration error. */
    private static final String SENTINEL = "sentinel-schema-value-7f3a";

    @TestFactory
    @DisplayName("binds db.schemas from the db section")
    Stream<DynamicTest> bindsDbSchemasFromDbSection() {
        return Stream.of(
                accepts("no db section", new JsonObject(), Map.of()),
                accepts("db section without schemas", withDb(new JsonObject().put("host", "db.example")), Map.of()),
                accepts("schemas is JSON null", withDb(new JsonObject().putNull("schemas")), Map.of()),
                accepts("schemas is an empty object", withSchemas(new JsonObject()), Map.of()),
                accepts(
                        "valid map including a key that matches no migration instance",
                        withSchemas(new JsonObject().put("job", "jobs").put("app", "app_schema")),
                        Map.of("job", "jobs", "app", "app_schema")),
                accepts(
                        "value is kept verbatim because the binding applies no vendor rules",
                        withSchemas(new JsonObject().put("job", "Mixed-Case.Schema 1")),
                        Map.of("job", "Mixed-Case.Schema 1")),
                rejects("schemas is a string", withDb(new JsonObject().put("schemas", SENTINEL)), "db.schemas"),
                rejects(
                        "schemas is an array",
                        withDb(new JsonObject().put("schemas", new JsonArray().add(SENTINEL))),
                        "db.schemas"),
                rejects("schemas is a number", withDb(new JsonObject().put("schemas", 7)), "db.schemas"),
                rejects("value is a number", withSchemas(new JsonObject().put("job", 42)), "db.schemas.job"),
                rejects("value is a boolean", withSchemas(new JsonObject().put("job", true)), "db.schemas.job"),
                rejects(
                        "value is an object",
                        withSchemas(new JsonObject().put("job", new JsonObject().put("name", SENTINEL))),
                        "db.schemas.job"),
                rejects(
                        "value is an array",
                        withSchemas(new JsonObject().put("job", new JsonArray().add(SENTINEL))),
                        "db.schemas.job"),
                rejects("value is JSON null", withSchemas(new JsonObject().putNull("job")), "db.schemas.job"),
                rejects("value is empty", withSchemas(new JsonObject().put("job", "")), "db.schemas.job"),
                rejects("value is blank", withSchemas(new JsonObject().put("job", "   ")), "db.schemas.job"),
                rejects(
                        "malformed value next to a valid one names the offending key",
                        withSchemas(new JsonObject().put("app", "app_schema").put("job", 42)),
                        "db.schemas.job"),
                rejects("key is empty", withSchemas(new JsonObject().put("", SENTINEL)), "db.schemas"),
                rejects("key is blank", withSchemas(new JsonObject().put("  ", SENTINEL)), "db.schemas"),
                providedMappingsAreSnapshotOfConfig(),
                providedMappingsAreImmutable());
    }

    private static DynamicTest accepts(String shape, JsonObject config, Map<String, String> expectedMappings) {
        return DynamicTest.dynamicTest("accepts: " + shape, () -> {
            // Given: the application config in this shape

            // When: the provider reads it
            DbSchemas schemas = DbModule.dbSchemas(config);

            // Then: exactly the expected mappings are bound
            assertEquals(expectedMappings, schemas.mappings());
            expectedMappings.forEach((name, schema) -> assertEquals(Optional.of(schema), schemas.schema(name)));
            assertEquals(Optional.empty(), schemas.schema("unmapped-name"));
        });
    }

    private static DynamicTest rejects(String shape, JsonObject config, String namedSetting) {
        return DynamicTest.dynamicTest("rejects: " + shape, () -> {
            // Given: the application config in this malformed shape

            // When: the provider reads it
            ConfigurationException failure =
                    assertThrows(ConfigurationException.class, () -> DbModule.dbSchemas(config));

            // Then: the message names the setting and no part of the chain echoes the configured value
            assertTrue(
                    failure.getMessage().contains(namedSetting),
                    () -> "message should name " + namedSetting + " but was: " + failure.getMessage());
            for (Throwable t = failure; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains(SENTINEL), "value echoed in " + t.getClass());
            }
        });
    }

    private static DynamicTest providedMappingsAreSnapshotOfConfig() {
        return DynamicTest.dynamicTest("the bound mappings do not follow later changes to the config", () -> {
            // Given: a provided binding
            JsonObject schemasSection = new JsonObject().put("job", "jobs");
            DbSchemas schemas = DbModule.dbSchemas(withSchemas(schemasSection));

            // When: the source config changes afterwards
            schemasSection.put("late", "late_schema").put("job", "changed");

            // Then: the binding is unchanged
            assertEquals(Map.of("job", "jobs"), schemas.mappings());
        });
    }

    private static DynamicTest providedMappingsAreImmutable() {
        return DynamicTest.dynamicTest("the bound mappings cannot be modified", () -> {
            // Given: a provided binding
            DbSchemas schemas = DbModule.dbSchemas(withSchemas(new JsonObject().put("job", "jobs")));

            // When / Then: every modification attempt fails and the binding is unchanged
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .put("app", "app_schema"));
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .remove("job"));
            assertEquals(Map.of("job", "jobs"), schemas.mappings());
        });
    }

    private static JsonObject withDb(JsonObject db) {
        return new JsonObject().put("db", db);
    }

    private static JsonObject withSchemas(JsonObject schemas) {
        return withDb(new JsonObject().put("schemas", schemas));
    }

    private List<String> warnMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
