// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DbPoolConfig} secret hygiene.
 *
 * <p>The password, the trust-store password, and the vendor properties bag are available to the
 * runtime but must never leak out. These tests pin both halves of the contract: each secret is
 * readable through its accessor after deserialization, yet the config mapper serializes the two
 * passwords as {@code "***"} and omits the write-only properties bag, and every secret is redacted
 * in {@link DbPoolConfig#toString()}.
 */
@DisplayName("DbPoolConfig redaction")
class DbPoolConfigRedactionTest {

    private static final String PASSWORD = "pool-secret-1";
    private static final String TRUST_STORE_PASSWORD = "trust-secret-2";
    private static final String PROPERTY_VALUE = "property-secret-3";

    private final ObjectMapper mapper = DefaultConfigMapper.lenient();

    private DbPoolConfig deserialized() throws Exception {
        String json = """
                {
                  "host": "db.example",
                  "port": 5432,
                  "database": "mydb",
                  "user": "app",
                  "password": "%s",
                  "trustStorePassword": "%s",
                  "properties": { "sslpassword": "%s" }
                }
                """.formatted(PASSWORD, TRUST_STORE_PASSWORD, PROPERTY_VALUE);
        return mapper.readValue(json, DbPoolConfig.class);
    }

    @Test
    @DisplayName("secrets deserialize and are readable through their accessors")
    void secrets_readableAfterDeserialization() throws Exception {
        DbPoolConfig config = deserialized();

        assertEquals(PASSWORD, config.password());
        assertEquals(TRUST_STORE_PASSWORD, config.trustStorePassword());
        assertEquals(Map.of("sslpassword", PROPERTY_VALUE), config.properties());
    }

    @Test
    @DisplayName("the config mapper masks the passwords as *** and omits the write-only properties bag")
    void secrets_maskedByConfigMapper() throws Exception {
        String serialized = mapper.writeValueAsString(deserialized());

        assertFalse(serialized.contains(PASSWORD), "password value must not serialize");
        assertFalse(serialized.contains(TRUST_STORE_PASSWORD), "trust-store password value must not serialize");
        assertFalse(serialized.contains(PROPERTY_VALUE), "vendor property value must not serialize");
        JsonObject json = new JsonObject(serialized);
        assertEquals("***", json.getString("password"));
        assertEquals("***", json.getString("trustStorePassword"));
        assertFalse(json.containsKey("properties"), "properties key must not serialize");
        assertEquals("db.example", json.getString("host"), "non-secret fields still serialize");
    }

    @Test
    @DisplayName("absent passwords are omitted from the config mapper's output")
    void absentSecrets_omittedByConfigMapper() throws Exception {
        DbPoolConfig config = mapper.readValue("{\"host\":\"db.example\"}", DbPoolConfig.class);

        JsonObject json = new JsonObject(mapper.writeValueAsString(config));

        assertFalse(json.containsKey("password"), json.encode());
        assertFalse(json.containsKey("trustStorePassword"), json.encode());
    }

    @Test
    @DisplayName("toString redacts every secret and keeps the non-secret fields")
    void secrets_redactedInToString() throws Exception {
        String rendered = deserialized().toString();

        assertFalse(rendered.contains(PASSWORD), "password must not appear in toString");
        assertFalse(rendered.contains(TRUST_STORE_PASSWORD), "trust-store password must not appear in toString");
        assertFalse(rendered.contains(PROPERTY_VALUE), "vendor property value must not appear in toString");
        assertTrue(rendered.contains("password=<redacted>"));
        assertTrue(rendered.contains("trustStorePassword=<redacted>"));
        assertTrue(rendered.contains("properties=<redacted>"));
        assertTrue(rendered.contains("host=db.example"));
        assertTrue(rendered.contains("user=app"));
    }

    @Test
    @DisplayName("the builder's toString redacts secrets too")
    void builder_redactedInToString() {
        String rendered = DbPoolConfig.builder()
                .host("db.example")
                .password(PASSWORD)
                .trustStorePassword(TRUST_STORE_PASSWORD)
                .properties(Map.of("sslpassword", PROPERTY_VALUE))
                .toString();

        assertFalse(rendered.contains(PASSWORD), "password must not appear in the builder's toString");
        assertFalse(rendered.contains(TRUST_STORE_PASSWORD));
        assertFalse(rendered.contains(PROPERTY_VALUE));
        assertTrue(rendered.contains("host=db.example"));
    }

    @Test
    @DisplayName("absent secrets render as null and an empty properties bag as {}")
    void absentSecrets_renderWithoutRedactionMarker() {
        String rendered = DbPoolConfig.builder().host("h").build().toString();

        assertTrue(rendered.contains("password=null"));
        assertTrue(rendered.contains("trustStorePassword=null"));
        assertTrue(rendered.contains("properties={}"));
    }
}
