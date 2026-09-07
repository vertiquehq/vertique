// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FlywayConfig} secret hygiene: the DDL password is readable after
 * deserialization, excluded from Jackson serialization ({@code WRITE_ONLY}), and redacted in the
 * class and builder {@code toString()}.
 */
@DisplayName("FlywayConfig redaction")
class FlywayConfigRedactionTest {

    private static final String PASSWORD = "ddl-secret-1";
    private static final String PLACEHOLDER_SECRET = "role-secret-2";
    private static final String URL_SECRET = "url-secret-3";

    private final ObjectMapper mapper = new ObjectMapper();

    private FlywayConfig deserialized() throws Exception {
        String json = """
                { "mode": "VALIDATE",
                  "jdbcUrl": "jdbc:postgresql://db.example:5432/app?password=%s&ssl=true",
                  "user": "ddl", "password": "%s",
                  "placeholders": { "appPassword": "%s" } }
                """.formatted(URL_SECRET, PASSWORD, PLACEHOLDER_SECRET);
        return mapper.readValue(json, FlywayConfig.class);
    }

    @Test
    @DisplayName("secrets deserialize and are readable through their accessors")
    void password_readableAfterDeserialization() throws Exception {
        FlywayConfig config = deserialized();

        assertEquals(PASSWORD, config.password());
        assertEquals(java.util.Map.of("appPassword", PLACEHOLDER_SECRET), config.placeholders());
        assertTrue(config.jdbcUrl().contains("password=" + URL_SECRET), "the URL is stored as given");
    }

    @Test
    @DisplayName("password is excluded from Jackson serialization (WRITE_ONLY)")
    void password_writeOnly() throws Exception {
        String serialized = mapper.writeValueAsString(deserialized());

        assertFalse(serialized.contains(PASSWORD), "password value must not serialize");
        assertFalse(serialized.contains("\"password\""), "password key must not serialize");
        assertFalse(serialized.contains(PLACEHOLDER_SECRET), "placeholder value must not serialize");
        assertFalse(serialized.contains("\"placeholders\""), "placeholders key must not serialize");
        assertTrue(serialized.contains("\"user\":\"ddl\""), "non-secret fields still serialize");
    }

    @Test
    @DisplayName("class and builder toString render the password redacted")
    void password_redactedInToString() throws Exception {
        String rendered = deserialized().toString();
        String builderRendered =
                FlywayConfig.builder().user("ddl").password(PASSWORD).toString();

        assertFalse(rendered.contains(PASSWORD));
        assertTrue(rendered.contains("password=<redacted>"));
        assertTrue(rendered.contains("user=ddl") && rendered.contains("mode=VALIDATE"));
        assertFalse(rendered.contains(PLACEHOLDER_SECRET), "placeholder value must not render");
        assertTrue(rendered.contains("placeholders=[appPassword]"), rendered);
        assertFalse(rendered.contains(URL_SECRET), "a password embedded in the URL must not render");
        assertTrue(
                rendered.contains("jdbcUrl=jdbc:postgresql://db.example:5432/app?password=<redacted>&ssl=true"),
                rendered);
        assertFalse(FlywayConfig.builder()
                .jdbcUrl("jdbc:postgresql://h/d?password=" + URL_SECRET)
                .toString()
                .contains(URL_SECRET));
        assertFalse(builderRendered.contains(PASSWORD));
        assertTrue(builderRendered.contains("password=<redacted>"));
    }

    @Test
    @DisplayName("an absent password renders as null")
    void absentPassword_rendersNull() {
        assertTrue(FlywayConfig.builder().build().toString().contains("password=null"));
    }
}
