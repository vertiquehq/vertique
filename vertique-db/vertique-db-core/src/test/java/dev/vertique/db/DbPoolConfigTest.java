// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DbPoolConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDeserializeMinimalConfig() throws Exception {
        String json = """
                {
                  "host": "localhost",
                  "port": 5432,
                  "database": "mydb",
                  "user": "app",
                  "password": "secret"
                }
                """;

        DbPoolConfig config = mapper.readValue(json, DbPoolConfig.class);
        assertEquals("localhost", config.host());
        assertEquals(5432, config.port());
        assertEquals("mydb", config.database());
        assertEquals("app", config.user());
        assertEquals("secret", config.password());
    }

    @Test
    void shouldHaveCorrectDefaults() {
        DbPoolConfig config = DbPoolConfig.builder().build();
        assertEquals(5, config.maxPoolSize());
        assertEquals(-1, config.maxWaitQueueSize());
        assertEquals(0, config.eventLoopSize());
        assertEquals(30_000, config.connectionTimeoutMs());
        assertEquals(0, config.idleTimeoutMs());
        assertEquals(0, config.maxLifetimeMs());
        assertEquals(1_000, config.poolCleanerPeriodMs());
        assertFalse(config.cachePreparedStatements());
        assertEquals(256, config.preparedStatementCacheMaxSize());
        assertEquals(0, config.reconnectAttempts());
        assertEquals(1_000, config.reconnectIntervalMs());
        assertTrue(config.properties().isEmpty());
    }

    @Test
    void shouldDeserializeFullConfig() throws Exception {
        String json = """
                {
                  "host": "db.example.com",
                  "port": 3306,
                  "database": "prod",
                  "user": "admin",
                  "password": "p@ss",
                  "maxPoolSize": 20,
                  "maxWaitQueueSize": 100,
                  "connectionTimeoutMs": 5000,
                  "idleTimeoutMs": 60000,
                  "maxLifetimeMs": 3600000,
                  "cachePreparedStatements": true,
                  "properties": {"ApplicationName": "myapp"}
                }
                """;

        DbPoolConfig config = mapper.readValue(json, DbPoolConfig.class);
        assertEquals(20, config.maxPoolSize());
        assertEquals(100, config.maxWaitQueueSize());
        assertEquals(5000, config.connectionTimeoutMs());
        assertEquals(60000, config.idleTimeoutMs());
        assertEquals(3600000, config.maxLifetimeMs());
        assertTrue(config.cachePreparedStatements());
        assertEquals("myapp", config.properties().get("ApplicationName"));
    }

    @Test
    void shouldIgnoreUnknownProperties() throws Exception {
        String json = """
                {
                  "host": "localhost",
                  "port": 5432,
                  "unknownField": "value"
                }
                """;

        DbPoolConfig config = mapper.readValue(json, DbPoolConfig.class);
        assertEquals("localhost", config.host());
    }

    // --- SSL field deserialization ---

    @Test
    void shouldDeserializeSslFields() throws Exception {
        String json = """
                {
                  "host": "db.example.com",
                  "port": 5432,
                  "database": "mydb",
                  "user": "app",
                  "password": "secret",
                  "sslMode": "REQUIRE",
                  "trustAll": true,
                  "trustStorePath": "/certs/ca.pem",
                  "trustStorePassword": "changeit",
                  "trustStoreType": "PEM",
                  "keyPath": "/certs/client.key",
                  "certPath": "/certs/client.crt"
                }
                """;

        DbPoolConfig config = mapper.readValue(json, DbPoolConfig.class);
        assertEquals("REQUIRE", config.sslMode());
        assertTrue(config.trustAll());
        assertEquals("/certs/ca.pem", config.trustStorePath());
        assertEquals("changeit", config.trustStorePassword());
        assertEquals("PEM", config.trustStoreType());
        assertEquals("/certs/client.key", config.keyPath());
        assertEquals("/certs/client.crt", config.certPath());
    }

    @Test
    void shouldHaveSslDefaults() {
        DbPoolConfig config = DbPoolConfig.builder().build();
        assertEquals("DISABLE", config.sslMode());
        assertFalse(config.trustAll());
        assertNull(config.trustStorePath());
        assertNull(config.trustStorePassword());
        assertNull(config.trustStoreType());
        assertNull(config.keyPath());
        assertNull(config.certPath());
    }

    // --- validate() ---

    @Test
    void validateShouldWarnWhenHostIsNull() {
        DbPoolConfig config = DbPoolConfig.builder().maxPoolSize(5).build();
        List<String> warnings = config.validate();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("host")));
    }

    @Test
    void validateShouldWarnWhenMaxPoolSizeIsZero() {
        DbPoolConfig config =
                DbPoolConfig.builder().host("localhost").maxPoolSize(0).build();
        List<String> warnings = config.validate();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("maxPoolSize")));
    }

    @Test
    void validateShouldWarnWhenTrustStorePathSetButSslModeDisabled() {
        DbPoolConfig config = DbPoolConfig.builder()
                .host("localhost")
                .maxPoolSize(5)
                .sslMode("DISABLE")
                .trustStorePath("/certs/ca.pem")
                .build();
        List<String> warnings = config.validate();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("trustStorePath") && w.contains("DISABLE")));
    }

    @Test
    void validateShouldReturnNoWarningsForValidConfig() {
        DbPoolConfig config = DbPoolConfig.builder()
                .host("localhost")
                .port(5432)
                .database("mydb")
                .user("app")
                .password("secret")
                .maxPoolSize(5)
                .build();
        List<String> warnings = config.validate();
        assertTrue(warnings.isEmpty());
    }
}
