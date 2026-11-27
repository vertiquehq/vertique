// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The input-direction generator's custom-deserializer refusal must apply only to a bean-like type
 * whose own deserializer was replaced, never to a scalar, container, node, or Vert.x type that
 * takes its standard schema.
 *
 * <p>Proves a DTO carrying every such member kind — {@link JsonObject}, {@link
 * JsonArray}, {@link Buffer}, {@link com.fasterxml.jackson.databind.JsonNode}, {@link Optional},
 * {@link OptionalInt}, {@code java.time} types, {@link BigDecimal} (exercised under {@code
 * vertique-strict}'s own string wire form), {@link UUID}, an enum, a {@link Map}, a {@link List}, and
 * an array member — generates without throwing under all three built-in profiles: {@code system},
 * {@code vertique}, and {@code vertique-strict}. Vert.x's module-registered {@code
 * JsonObjectDeserializer} / {@code JsonArrayDeserializer} / {@code BufferDeserializer} do not extend
 * a bean deserializer, so {@code InputPropertyDescriber.describe} must not refuse a type merely
 * because its resolved deserializer is not a {@code BeanDeserializerBase}.
 */
class McpBuiltinProfileScalarAndContainerCoverageTest {

    enum Color {
        RED,
        GREEN,
        BLUE
    }

    /** Every member kind named above, on one type, so one generation call proves the whole list at once. */
    public static final class Kitchen {
        public JsonObject json;
        public JsonArray jsonArray;
        public Buffer buffer;
        public com.fasterxml.jackson.databind.JsonNode node;
        public Optional<String> optionalString;
        public OptionalInt optionalInt;
        public Instant instant;
        public LocalDate localDate;
        public LocalDateTime localDateTime;
        public BigDecimal amount;
        public UUID id;
        public Color color;
        public Map<String, String> map;
        public List<String> list;
        public Set<String> set;
        public String[] array;
    }

    private static JsonProfileCodec profile(String id) {
        return new DefaultJsonProfileRegistry(Set.of()).profile(JsonProfileId.of(id));
    }

    @Test
    @DisplayName("system profile describes every scalar/container/node/Vert.x member without refusing")
    void systemProfileDescribesEveryMember() {
        assertDoesNotThrow(() ->
                AnnotationJsonSchemaGenerator.forInputProfile(profile("system")).generateCanonical(Kitchen.class));
    }

    @Test
    @DisplayName("vertique profile describes every scalar/container/node/Vert.x member without refusing")
    void vertiqueProfileDescribesEveryMember() {
        assertDoesNotThrow(() -> AnnotationJsonSchemaGenerator.forInputProfile(profile("vertique"))
                .generateCanonical(Kitchen.class));
    }

    @Test
    @DisplayName("vertique-strict profile describes every scalar/container/node/Vert.x member, BigDecimal included")
    void vertiqueStrictProfileDescribesEveryMember() {
        assertDoesNotThrow(() -> AnnotationJsonSchemaGenerator.forInputProfile(profile("vertique-strict"))
                .generateCanonical(Kitchen.class));
    }
}
