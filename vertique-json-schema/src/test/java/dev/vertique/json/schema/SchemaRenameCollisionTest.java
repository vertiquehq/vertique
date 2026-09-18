// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A member walked by the schema library that Jackson does not attach to any property, renamed with
 * {@code @Schema(name = ...)} to a name another Jackson property already carries, is refused rather
 * than described with that other property's visibility and wire name (vertiquehq/vertique-dev#596).
 */
class SchemaRenameCollisionTest {

    /**
     * {@code note} is private with no accessor, so Jackson never reads or writes it; {@code x} is a
     * visible property published on the wire as {@code wire}. Before the refusal, both directions
     * published {@code "x": {"type": "integer"}} — a key Jackson binds to nothing — carrying the
     * visibility verdict looked up for {@code x}.
     */
    static final class RenamedOntoVisibleProperty {
        @JsonProperty("wire")
        public String x;

        @Schema(name = "x")
        private Integer note;
    }

    /**
     * {@code x} is read-only, so it is invisible on input. Before the refusal, the input schema was
     * the empty object: {@code note} was hidden with {@code x}'s input verdict.
     */
    static final class RenamedOntoReadOnlyProperty {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String x;

        @Schema(name = "x")
        private Integer note;
    }

    /** A rename to the member's own name: the fallback, if consulted, finds this same member. */
    static final class RenamedOntoItself {
        @Schema(name = "x")
        public String x;
    }

    /**
     * {@code x}'s private field is dropped from Jackson's output property, which keeps only the
     * setter, so the walked field misses by identity and the fallback finds its own property under
     * its own, unrenamed name. {@code note} is renamed to a name no property carries.
     */
    static final class FallbackToOwnPropertyAndRenameWithoutCollision {
        private String x;

        @Schema(name = "y")
        private Integer note;

        public void setX(String x) {
            this.x = x;
        }

        public Integer getNote() {
            return note;
        }
    }

    private static final JsonMapperProfile PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("rename-collision-test"), new ObjectMapper());

    @Test
    void shouldRefuseARenameOntoAnotherVisiblePropertysNameInBothDirections() {
        assertAll(
                refused(
                        () -> AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(RenamedOntoVisibleProperty.class),
                        RenamedOntoVisibleProperty.class,
                        "wire"),
                refused(
                        () -> AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .generateCanonical(RenamedOntoVisibleProperty.class),
                        RenamedOntoVisibleProperty.class,
                        "wire"));
    }

    @Test
    void shouldRefuseARenameOntoAPropertyInvisibleInTheDirection() throws Throwable {
        refused(
                        () -> AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(RenamedOntoReadOnlyProperty.class),
                        RenamedOntoReadOnlyProperty.class,
                        "x")
                .execute();
    }

    @Test
    void shouldDescribeAFallbackThatFindsTheMembersOwnPropertyOrNothingExactlyAsBefore() {
        // Pinned to what the generator published before the refusal existed.
        assertAll(
                () -> assertEquals(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + "\"properties\":{\"x\":{\"type\":\"string\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(RenamedOntoItself.class)),
                () -> assertEquals(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + "\"properties\":{\"x\":{\"type\":\"string\"},\"y\":{\"type\":\"integer\"}},"
                                + "\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(FallbackToOwnPropertyAndRenameWithoutCollision.class)),
                () -> assertEquals(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + "\"properties\":{\"y\":{\"type\":\"integer\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .generateCanonical(FallbackToOwnPropertyAndRenameWithoutCollision.class)));
    }

    private static Executable refused(Executable generation, Class<?> type, String collidingProperty) {
        return () -> {
            JsonSchemaGenerationException refusal = assertThrows(
                    JsonSchemaGenerationException.class,
                    generation,
                    "a member renamed onto another property's name must fail generation, not borrow that"
                            + " property's visibility and wire name");
            String message = refusal.getMessage();
            assertAll(
                    () -> assertTrue(message.contains(type.getTypeName()), message),
                    () -> assertTrue(message.contains("\"note\""), message),
                    () -> assertTrue(message.contains("\"" + collidingProperty + "\""), message),
                    () -> assertTrue(message.contains("@JsonProperty"), message),
                    () -> assertFalse(message.contains("{"), "no schema fragment: " + message),
                    () -> assertFalse(message.contains("\"type\""), "no schema fragment: " + message));
        };
    }
}
