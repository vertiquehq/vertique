// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.config.parser.DefaultConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KafkaMessageKeyHashConfig} secret hygiene.
 *
 * <p>The HMAC secret is available to the runtime but must never leak out. These tests pin both
 * halves of the direct value contract: the secret is readable through {@link
 * KafkaMessageKeyHashConfig#hmacSecret()}, yet it is excluded from Jackson serialization ({@code
 * WRITE_ONLY}) and redacted in {@link KafkaMessageKeyHashConfig#toString()}.
 */
@DisplayName("KafkaMessageKeyHashConfig redaction")
class KafkaMessageKeyHashConfigRedactionTest {

    // --- Tests ---

    @Test
    @DisplayName("toString does not reveal the HMAC secret")
    void hmacSecret_redactedInToString() {
        KafkaMessageKeyHashConfig config = new KafkaMessageKeyHashConfig("super-secret-key");

        String rendered = config.toString();
        assertFalse(rendered.contains("super-secret-key"), "the secret must not appear in toString");
        assertEquals("KafkaMessageKeyHashConfig[hmacSecret=<redacted>]", rendered);

        // A null secret renders without a redaction marker.
        assertEquals("KafkaMessageKeyHashConfig[hmacSecret=null]", new KafkaMessageKeyHashConfig(null).toString());
    }

    @Test
    @DisplayName("secret is directly readable but excluded from Jackson serialization (WRITE_ONLY)")
    void hmacSecret_writeOnly() throws JsonProcessingException {
        KafkaMessageKeyHashConfig config = new KafkaMessageKeyHashConfig("s");

        assertEquals("s", config.hmacSecret());

        String serialized = DefaultConfigMapper.lenient().writeValueAsString(config);
        assertFalse(serialized.contains("hmacSecret"), "WRITE_ONLY secret must not serialize");
        assertFalse(serialized.contains("\"s\""), "the secret value must not serialize");
    }

    @Test
    @DisplayName("null hmacSecret remains directly readable as null")
    void nullHmacSecret() {
        KafkaMessageKeyHashConfig config = new KafkaMessageKeyHashConfig(null);
        assertNull(config.hmacSecret());
    }
}
