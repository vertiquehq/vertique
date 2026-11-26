// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.util.StdConverter;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Property;
import java.lang.reflect.Type;
import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins resolver behaviour that no other test covers: a mapper-wide default typer, a content converter,
 * a mix-in, a Lombok builder and the surrogate-pair cut of a log path.
 */
@DisplayName("JakartaPathPointers coverage gaps")
class JakartaPathPointersCoverageGapsTest {

    /** A final record. The resolver stops at it under any default typer, whether or not the typer would type it. */
    public record Strings(List<String> items) {}

    /** A final element type, so only the list holding it can carry type information. */
    public record Row(String name) {}

    public record Rows(List<Row> items) {}

    public record Converted(
            @JsonDeserialize(contentConverter = Upper.class) List<String> items) {}

    public static final class Upper extends StdConverter<String, String> {
        @Override
        public String convert(String value) {
            return value.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Named by a mix-in, not by its own annotations. */
    public static final class Mixed {
        private String name;

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    abstract static class MixedMixIn {
        @JsonProperty("full_name")
        abstract void setName(String name);
    }

    @Jacksonized
    @Builder
    public static final class Built {
        private final String name;
    }

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static ObjectMapper defaultTyped() {
        return JsonMapper.builder()
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType(Object.class)
                                .build(),
                        ObjectMapper.DefaultTyping.NON_FINAL)
                .build();
    }

    @Test
    @DisplayName("a mapper-wide default typer stops the pointer at the root, so no list position is ever named")
    void defaultTyperStopsAtTheRoot() {
        // Given: a mapper whose default typer wraps lists as ["java.util.ArrayList", [...]], so position 1
        // of the Java list is not position 1 of the array
        ObjectMapper mapper = defaultTyped();
        PropertyPath inScalarList = path(new Property("items", Strings.class), new Index(1));
        PropertyPath inBeanList =
                path(new Property("items", Rows.class), new Index(0), new Property("name", Row.class));

        // When: a position in a scalar list and a member of a bean list resolve
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(mapper, null);
        String scalar = session.toPointer(inScalarList, Strings.class, null);
        String bean = session.toPointer(inBeanList, Rows.class, null);

        // Then: both stop at the root: the typer applies to every type, so the bean holding the list is
        // never verified
        assertEquals("", scalar);
        assertEquals("", bean);
    }

    @Test
    @DisplayName("a mapper-wide default typer stops a root list before its first position")
    void defaultTyperStopsARootList() {
        // Given: the same mapper and a list that is the root value
        ObjectMapper mapper = defaultTyped();
        Type rootType = new TypeReference<List<String>>() {}.getType();

        // When: a position of the root list resolves
        String pointer = JakartaPathPointers.resolver(mapper, null).toPointer(path(new Index(1)), rootType, null);

        // Then: no position is named
        assertEquals("", pointer);
    }

    @Test
    @DisplayName("the same list resolves per position without a default typer")
    void theSameListResolvesWithoutADefaultTyper() {
        // Given: the process mapper and the same fixtures
        PropertyPath violated = path(new Property("items", Rows.class), new Index(0), new Property("name", Row.class));

        // When: a member of the first element resolves
        String pointer =
                JakartaPathPointers.resolver(VertiqueJson.mapper(), null).toPointer(violated, Rows.class, null);

        // Then: every level is named
        assertEquals("/items/0/name", pointer);
    }

    @Test
    @DisplayName("a content converter on the list property ends the pointer after the list")
    void contentConverterEndsThePointerAfterTheList() {
        // Given: a list property whose elements a converter rebuilds
        PropertyPath violated = path(new Property("items", Converted.class), new Index(0));

        // When: its first position resolves
        String pointer =
                JakartaPathPointers.resolver(VertiqueJson.mapper(), null).toPointer(violated, Converted.class, null);

        // Then: the list is named and no position below it
        assertEquals("/items", pointer);
    }

    @Test
    @DisplayName("a mix-in that renames a property names the wire name")
    void mixInRenamesTheProperty() {
        // Given: a mapper that applies a mix-in to a class without annotations
        ObjectMapper mapper =
                JsonMapper.builder().addMixIn(Mixed.class, MixedMixIn.class).build();
        PropertyPath violated = path(new Property("name", Mixed.class));

        // When: the property resolves
        String pointer = JakartaPathPointers.resolver(mapper, null).toPointer(violated, Mixed.class, null);

        // Then: the pointer carries the mix-in's name
        assertEquals("/full_name", pointer);
    }

    @Test
    @DisplayName("a Lombok builder bean stops the pointer at the value holding it")
    void lombokBuilderBeanStopsAtTheRoot() {
        // Given: a bean bound through its generated builder
        PropertyPath violated = path(new Property("name", Built.class));

        // When: its property resolves
        String pointer =
                JakartaPathPointers.resolver(VertiqueJson.mapper(), null).toPointer(violated, Built.class, null);

        // Then: the builder's own property names are not trusted
        assertEquals("", pointer);
    }

    @Test
    @DisplayName("a log path cut at the length limit never splits a surrogate pair")
    void logPathCutKeepsSurrogatePairsWhole() {
        // Given: a name whose last character is a pair that straddles the 512-character limit
        String name = "a".repeat(511) + "😀";

        // When: it is rendered for a log line
        String rendered = assertDoesNotThrow(() -> JakartaPathPointers.loggablePath(name));

        // Then: the pair is dropped whole
        assertEquals("a".repeat(511), rendered);
        assertFalse(Character.isHighSurrogate(rendered.charAt(rendered.length() - 1)));
    }
}
