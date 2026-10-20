// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Property;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the projection mode of {@link JakartaPathPointers}: with the route's Jackson-backed name
 * projection, a property is named by the wire spelling its route binds, a document key counts only
 * when it is a declared spelling of the property, and a bean the projection cannot enumerate ends the
 * pointer at the property holding it.
 *
 * <p>Lives in this module because the projection is implemented here; the resolver itself is in the
 * core module, which this module depends on.
 */
class JakartaPathPointersProjectionTest {

    // --- Fixtures ---

    /** A single-component record renamed only by a SNAKE_CASE naming strategy. */
    public record Address(String zipCode) {}

    /** Reads whatever it likes, outside the declaration view. */
    public static class CustomDeserializer extends JsonDeserializer<Custom> {
        @Override
        public Custom deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            Custom custom = new Custom();
            custom.setX(node.path("x").asText(null));
            return custom;
        }
    }

    /**
     * A bean bound by a class-level custom deserializer. Its property {@code x} is visible to plain
     * introspection, so only the projection's refusal to enumerate it can stop the pointer.
     */
    @JsonDeserialize(using = CustomDeserializer.class)
    public static class Custom {
        private String x;

        public String getX() {
            return x;
        }

        public void setX(String x) {
            this.x = x;
        }
    }

    /** Holds the custom-deserialized bean. */
    public record Outer(Custom inner) {}

    private static ObjectMapper snakeCaseMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

    /** One resolution session in projection mode over {@code mapper} and its own name projection. */
    private static JakartaPathPointers.Resolver session(ObjectMapper mapper) {
        return JakartaPathPointers.resolver(mapper, JacksonFieldNameResolver.forMapper(mapper));
    }

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static final PropertyPath ZIP_CODE = path(new Property("zipCode", Address.class));

    private static final PropertyPath INNER_X =
            path(new Property("inner", Outer.class), new Property("x", Custom.class));

    // --- Tests ---

    @Test
    @DisplayName("projection mode names properties through the route's projection")
    void projectionModeNamesThroughTheRoutesProjection() {
        // Given: a SNAKE_CASE route and the process mapper's route, each with its own projection
        ObjectMapper snake = snakeCaseMapper();
        ObjectMapper process = VertiqueJson.mapper();
        JsonObject declaredKey = new JsonObject().put("zip_code", "");
        JsonObject javaNameKey = new JsonObject().put("zipCode", "");
        JsonObject declaredAndJavaNameKeys =
                new JsonObject().put("zip_code", "").put("zipCode", "x");
        JsonObject innerDocument = new JsonObject().put("inner", new JsonObject().put("x", ""));

        // When: the same structured paths resolve with and without a document
        String snakeWithDocument = session(snake).toPointer(ZIP_CODE, Address.class, declaredKey);
        String snakeWithoutDocument = session(snake).toPointer(ZIP_CODE, Address.class, null);
        String processWithoutDocument = session(process).toPointer(ZIP_CODE, Address.class, null);
        String processWithDocument = session(process).toPointer(ZIP_CODE, Address.class, javaNameKey);
        String snakeWithJavaNameKey = session(snake).toPointer(ZIP_CODE, Address.class, javaNameKey);
        String snakeWithBothKeys = session(snake).toPointer(ZIP_CODE, Address.class, declaredAndJavaNameKeys);
        String customWithoutDocument = session(snake).toPointer(INNER_X, Outer.class, null);
        String customWithDocument = session(snake).toPointer(INNER_X, Outer.class, innerDocument);

        // Then: the route's wire spelling names the property; a Java-name key is never a declared spelling;
        // a bean the projection cannot enumerate ends the pointer at the property holding it
        assertAll(
                () -> assertEquals("/zip_code", snakeWithDocument, "SNAKE_CASE route, declared key present"),
                () -> assertEquals("/zip_code", snakeWithoutDocument, "SNAKE_CASE route, no document"),
                () -> assertEquals("/zipCode", processWithoutDocument, "process mapper route, no document"),
                () -> assertEquals("/zipCode", processWithDocument, "process mapper route, declared key present"),
                () -> assertTrue(
                        Set.of("", "/zip_code").contains(snakeWithJavaNameKey),
                        "a Java-name key on a renaming route is not a declared spelling and must never be"
                                + " emitted; got '" + snakeWithJavaNameKey + "'"),
                () -> assertEquals("/zip_code", snakeWithBothKeys, "declared key beside a stray Java-name key"),
                () -> assertEquals("/inner", customWithoutDocument, "custom-deserialized bean, no document"),
                () -> assertEquals("/inner", customWithDocument, "custom-deserialized bean, document present"));
    }

    @Test
    @DisplayName("a projection that throws truncates the pointer and never escapes the resolver")
    void aThrowingProjectionTruncatesInsteadOfFailing() {
        // Given: a projection whose every answer fails, as an unwarmed projection with a name collision does
        InputFieldNameResolver failing = new InputFieldNameResolver() {
            @Override
            public String logicalName(Class<?> ownerType, String wireName) {
                throw new IllegalStateException("projection cannot be composed");
            }

            @Override
            public Set<String> boundJavaNames(Class<?> ownerType) {
                throw new IllegalStateException("projection cannot be composed");
            }

            @Override
            public Map<String, PromotedField> promotedFields(Class<?> ownerType) {
                throw new IllegalStateException("projection cannot be composed");
            }

            @Override
            public Set<Class<?>> polymorphicSubtypes(Class<?> ownerType) {
                throw new IllegalStateException("projection cannot be composed");
            }
        };
        ObjectMapper snake = snakeCaseMapper();
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(snake, failing);

        // When: the same path resolves twice in one session, with and without a document
        String withDocument = session.toPointer(ZIP_CODE, Address.class, new JsonObject().put("zip_code", ""));
        String withoutDocument = session.toPointer(ZIP_CODE, Address.class, null);

        // Then: nothing below the root can be verified, so both pointers stop at the root
        assertAll(
                () -> assertEquals("", withDocument, "failed projection, document present"),
                () -> assertEquals("", withoutDocument, "failed projection, no document"));
    }
}
