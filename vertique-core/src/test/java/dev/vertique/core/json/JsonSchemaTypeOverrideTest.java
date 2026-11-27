// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import io.vertx.core.buffer.Buffer;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonSchemaTypeOverride} factory validation and component exposure, and for
 * the {@link JsonProfileCodec#jsonSchemaTypeOverrides()} default.
 */
class JsonSchemaTypeOverrideTest {

    private static final JsonSchemaFragment FRAGMENT = JsonSchemaFragment.parse("{\"type\":\"string\"}");

    @Test
    @DisplayName("factories reject null arguments and expose the supplied components and direction")
    void overrideFactoriesRejectNullsAndExposeComponents() {
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.input(null, FRAGMENT));
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.input(BigDecimal.class, null));
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.output(null, FRAGMENT));
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.output(BigDecimal.class, null));
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.both(null, FRAGMENT));
        assertThrows(NullPointerException.class, () -> JsonSchemaTypeOverride.both(BigDecimal.class, null));

        JsonSchemaTypeOverride input = JsonSchemaTypeOverride.input(BigDecimal.class, FRAGMENT);
        assertEquals(BigDecimal.class, input.javaType());
        assertEquals(Direction.INPUT, input.direction());
        assertSame(FRAGMENT, input.fragment());

        JsonSchemaTypeOverride output = JsonSchemaTypeOverride.output(String.class, FRAGMENT);
        assertEquals(String.class, output.javaType());
        assertEquals(Direction.OUTPUT, output.direction());
        assertSame(FRAGMENT, output.fragment());

        JsonSchemaTypeOverride both = JsonSchemaTypeOverride.both(Integer.class, FRAGMENT);
        assertEquals(Integer.class, both.javaType());
        assertEquals(Direction.BOTH, both.direction());
        assertSame(FRAGMENT, both.fragment());
    }

    @Test
    @DisplayName("a profile implementing only the neutral members reports a stable empty unmodifiable override list")
    void profileDefaultOverridesIsEmptyStableUnmodifiable() {
        JsonProfileCodec profile = new JsonProfileCodec() {

            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("legacy");
            }

            @Override
            public Buffer encode(Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String encodeToString(Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void encodeTo(Object value, OutputStream out) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T decode(Buffer json, Type type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T decode(String json, Type type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T convert(Object value, Type type) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Optional<T> unwrap(Class<T> type) {
                return Optional.empty();
            }
        };

        List<JsonSchemaTypeOverride> overrides = profile.jsonSchemaTypeOverrides();

        assertNotNull(overrides, "default override list must not be null");
        assertTrue(overrides.isEmpty(), "default override list must be empty");
        assertSame(overrides, profile.jsonSchemaTypeOverrides(), "default override list must be stable per call");
        assertThrows(
                UnsupportedOperationException.class,
                () -> overrides.add(JsonSchemaTypeOverride.input(BigDecimal.class, FRAGMENT)));
    }
}
