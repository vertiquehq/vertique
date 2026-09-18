// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

    /**
     * The Lombok-style alignment of a {@code boolean isX} field: Jackson names the accessor pair's
     * property {@code active} and does not join the {@code isActive} field to it, so the rename lands
     * on a property no walked field describes. The renamed field is the only thing describing
     * {@code active}, because getters are not walked, and the fallback's answer is correct.
     */
    static final class BooleanIsFieldAlignedWithItsAccessors {
        @Schema(name = "active")
        private boolean isActive;

        public boolean isActive() {
            return isActive;
        }

        public void setActive(boolean active) {
            this.isActive = active;
        }
    }

    /** The same idiom for a prefixed field: {@code mName} behind {@code getName()} and {@code setName()}. */
    static final class PrefixedFieldAlignedWithItsAccessors {
        @Schema(name = "name")
        private String mName;

        public String getName() {
            return mName;
        }

        public void setName(String name) {
            this.mName = name;
        }
    }

    /**
     * Under a snake-case mapper the component's input property is {@code first_name}, and the walked
     * field misses it by identity, so the lookup falls back to the rename — which lands on the field's
     * own property.
     */
    record ComponentRenamedToItsOwnSnakeCaseName(
            @Schema(name = "first_name") String firstName) {}

    /**
     * A field with no getter or setter, renamed to a name no property carries. Its value arrives through
     * the creator, and a mapper that does not use final fields as mutators leaves the field off the
     * input property, so the walked field misses by identity and the fallback finds nothing. (A private
     * field with no accessor at all never gets this far: the Jackson module ignores it first.)
     */
    static final class CreatorBoundFieldRenamedToAFreshName {
        @Schema(name = "label")
        public final String note;

        @JsonCreator
        CreatorBoundFieldRenamedToAFreshName(@JsonProperty("note") String note) {
            this.note = note;
        }
    }

    private static final JsonMapperProfile PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("rename-collision-test"), new ObjectMapper());

    private static final JsonMapperProfile SNAKE_CASE_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("rename-collision-snake-case-test"),
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    private static final JsonMapperProfile NO_FINAL_FIELD_MUTATORS_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("rename-collision-no-final-field-mutators-test"),
            JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
                    .build());

    private static final String SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",";

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

    @Test
    void shouldDescribeAFieldRenamedOntoItsOwnAccessorsPropertyExactlyAsBefore() {
        // Pinned to what the generator published before the refusal existed: the landed-on property
        // has no walked field of its own, so nothing else is published under the name.
        assertAll(
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"active\":{\"type\":\"boolean\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(BooleanIsFieldAlignedWithItsAccessors.class)),
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"active\":{\"type\":\"boolean\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .generateCanonical(BooleanIsFieldAlignedWithItsAccessors.class)),
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(PROFILE)
                                .generateCanonical(PrefixedFieldAlignedWithItsAccessors.class)),
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"name\":{\"type\":\"string\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .generateCanonical(PrefixedFieldAlignedWithItsAccessors.class)));
    }

    @Test
    void shouldDescribeRenamedMembersThatReachTheFallbackWithoutACollisionExactlyAsBefore() {
        // Both members miss by identity and reach the rename check, so a refusal that fires on any
        // renamed fallback turns this red. Pinned to what the generator published before the refusal.
        assertAll(
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"first_name\":{\"type\":\"string\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(SNAKE_CASE_PROFILE)
                                .generateCanonical(ComponentRenamedToItsOwnSnakeCaseName.class)),
                () -> assertEquals(
                        SCHEMA + "\"properties\":{\"label\":{\"type\":\"string\"}},\"type\":\"object\"}",
                        AnnotationJsonSchemaGenerator.forInputProfile(NO_FINAL_FIELD_MUTATORS_PROFILE)
                                .generateCanonical(CreatorBoundFieldRenamedToAFreshName.class)));
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
