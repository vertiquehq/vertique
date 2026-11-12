// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the secret hygiene of {@link HttpConfig} and its nested {@link SslConfig} on the config
 * mapper: the two keystore passwords are readable after parsing, serialize as {@code "***"} when
 * set, are omitted when absent, and a masked dump cannot be fed back as configuration.
 */
@DisplayName("HttpConfig secret masking")
class HttpConfigSecretMaskingTest {

    private static final String KEY_STORE_PASSWORD = "keystore-secret-1";
    private static final String TRUST_STORE_PASSWORD = "truststore-secret-2";

    private final ObjectMapper mapper = DefaultConfigMapper.lenient();

    private HttpConfig withPasswords() {
        JsonObject section = new JsonObject()
                .put("port", 8443)
                .put(
                        "ssl",
                        new JsonObject()
                                .put("enabled", true)
                                .put("keyStorePath", "/etc/tls/server.p12")
                                .put("keyStorePassword", KEY_STORE_PASSWORD)
                                .put("trustStorePath", "/etc/tls/clients.p12")
                                .put("trustStorePassword", TRUST_STORE_PASSWORD));
        return new DefaultConfigParser(mapper).parse(section, HttpConfig.class);
    }

    @Test
    @DisplayName("both passwords stay readable through their accessors")
    void passwordsReadableAfterParsing() {
        HttpConfig config = withPasswords();

        assertEquals(KEY_STORE_PASSWORD, config.ssl().keyStorePassword());
        assertEquals(TRUST_STORE_PASSWORD, config.ssl().trustStorePassword());
    }

    @Test
    @DisplayName("the config mapper serializes both passwords as *** and keeps the rest")
    void passwordsSerializeMasked() throws Exception {
        String serialized = mapper.writeValueAsString(withPasswords());

        assertFalse(serialized.contains(KEY_STORE_PASSWORD), serialized);
        assertFalse(serialized.contains(TRUST_STORE_PASSWORD), serialized);
        JsonObject ssl = new JsonObject(serialized).getJsonObject("ssl");
        assertEquals("***", ssl.getString("keyStorePassword"));
        assertEquals("***", ssl.getString("trustStorePassword"));
        assertTrue(ssl.getBoolean("enabled"));
        assertEquals(8443, new JsonObject(serialized).getInteger("port"));
    }

    @Test
    @DisplayName("absent passwords are omitted rather than masked")
    void absentPasswordsOmitted() throws Exception {
        HttpConfig config = new DefaultConfigParser(mapper)
                .parse(new JsonObject().put("ssl", new JsonObject().put("enabled", true)), HttpConfig.class);

        JsonObject ssl = new JsonObject(mapper.writeValueAsString(config)).getJsonObject("ssl");

        assertFalse(ssl.containsKey("keyStorePassword"), ssl.encode());
        assertFalse(ssl.containsKey("trustStorePassword"), ssl.encode());
    }

    @Test
    @DisplayName("a masked dump is rejected when parsed back")
    void maskedDumpRejectedOnParse() throws Exception {
        JsonObject dump = new JsonObject(mapper.writeValueAsString(withPasswords()));

        ConfigurationException failure = assertThrows(
                ConfigurationException.class, () -> new DefaultConfigParser(mapper).parse(dump, HttpConfig.class));

        // The scan reports the first masked leaf in key order; the shared rule's keystore token masks the
        // path and type fields too, so any ssl.keyStore* path proves the dump was refused.
        assertTrue(failure.getMessage().contains("'ssl.keyStore"), failure.getMessage());
        assertTrue(failure.getMessage().contains("***"), failure.getMessage());
        assertFalse(failure.getMessage().contains(KEY_STORE_PASSWORD));
    }
}
