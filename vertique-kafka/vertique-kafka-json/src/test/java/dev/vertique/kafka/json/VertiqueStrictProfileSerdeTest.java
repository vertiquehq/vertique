// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test proving {@link JsonSerdeProvider} resolves the built-in {@code vertique-strict} profile
 * by id and honors its strict {@link BigDecimal} string wire form.
 *
 * <p>Mirrors the direct-construction wiring used by {@link JsonSerdeProviderProfileTest} and
 * {@link JsonSerdeProviderTest}: the provider is built from a
 * {@link DefaultJsonMapperProfileRegistry} seeded with no application profiles (the {@code vertx},
 * {@code vertique}, and {@code vertique-strict} built-ins are always present) and
 * {@link JsonConfig#defaults()}, with the endpoint bag selecting {@code vertique-strict} via the
 * {@code jsonProfile} key.
 */
class VertiqueStrictProfileSerdeTest {

    /** Test record carrying a {@link BigDecimal} property, used to exercise the strict wire form. */
    record Money(BigDecimal amount) {}

    private final JsonSerdeProvider provider =
            new JsonSerdeProvider(new DefaultJsonMapperProfileRegistry(Set.of()), JsonConfig.defaults());

    private static JsonObject bagWithProfile(String profileId) {
        return new JsonObject().put("jsonProfile", profileId);
    }

    @Test
    @DisplayName("serializer() under vertique-strict writes BigDecimal as a JSON string")
    void serializer_underVertiqueStrict_writesBigDecimalAsString() {
        JsonObject bag = bagWithProfile("vertique-strict");
        KafkaSerializer<Money> serializer = provider.serializer(Money.class, bag);

        byte[] bytes = serializer.serialize(new Money(new BigDecimal("1.50")), "t", Map.of());
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"1.50\""), "amount must be serialized as the JSON string \"1.50\": " + json);
    }

    @Test
    @DisplayName("deserializer() under vertique-strict round-trips BigDecimal preserving scale")
    void deserializer_underVertiqueStrict_roundTripsPreservingScale() throws Exception {
        JsonObject bag = bagWithProfile("vertique-strict");
        KafkaDeserializer<Money> deserializer = provider.deserializer(Money.class, bag);

        byte[] bytes = "{\"amount\":\"1.50\"}".getBytes(StandardCharsets.UTF_8);
        Money decoded = deserializer.deserialize(bytes, "t", Map.of());

        assertEquals(0, decoded.amount().compareTo(new BigDecimal("1.50")));
        assertEquals(2, decoded.amount().scale(), "the wire scale must be preserved exactly");
    }
}
