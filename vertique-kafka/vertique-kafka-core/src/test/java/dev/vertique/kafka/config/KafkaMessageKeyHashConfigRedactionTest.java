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
 * KafkaMessageKeyHashConfig#hmacSecret()}, yet the config mapper serializes it as {@code "***"} (and
 * omits it when absent) and it is redacted in {@link KafkaMessageKeyHashConfig#toString()}.
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
    @DisplayName("secret is directly readable but the config mapper serializes it as ***")
    void hmacSecret_maskedByConfigMapper() throws JsonProcessingException {
        KafkaMessageKeyHashConfig config = new KafkaMessageKeyHashConfig("super-secret-key");

        assertEquals("super-secret-key", config.hmacSecret());

        String serialized = DefaultConfigMapper.lenient().writeValueAsString(config);
        assertEquals("{\"hmacSecret\":\"***\"}", serialized);
    }

    @Test
    @DisplayName("an absent secret is omitted by the config mapper")
    void nullHmacSecret_omittedByConfigMapper() throws JsonProcessingException {
        String serialized = DefaultConfigMapper.lenient().writeValueAsString(new KafkaMessageKeyHashConfig(null));
        assertEquals("{}", serialized);
    }

    @Test
    @DisplayName("null hmacSecret remains directly readable as null")
    void nullHmacSecret() {
        KafkaMessageKeyHashConfig config = new KafkaMessageKeyHashConfig(null);
        assertNull(config.hmacSecret());
    }
}
