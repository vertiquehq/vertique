// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Property;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that a type-level Jackson deserializer or converter, which may rebuild a value from any part
 * of its input, stops the pointer where the value starts: a position or a member below it is never
 * named, because the Java position says nothing about where the data sat in the document.
 *
 * <p>Each fixture is bound for real first, so the test shows the mapper moved the data.
 */
class JakartaPathPointersClassLevelReshapingTest {

    private static final ObjectMapper MAPPER = VertiqueJson.mapper();

    /** A plain setter-bound element. */
    public static class Item {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Reads its elements in reverse order. */
    public static class ReversingDeserializer extends JsonDeserializer<ReversedList> {
        @Override
        public ReversedList deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            List<Item> read = new ArrayList<>(context.readValue(
                    parser, context.getTypeFactory().constructCollectionType(List.class, Item.class)));
            Collections.reverse(read);
            ReversedList list = new ReversedList();
            list.addAll(read);
            return list;
        }
    }

    /** A list bound by a type-level custom deserializer that reverses its input. */
    @JsonDeserialize(using = ReversingDeserializer.class)
    public static class ReversedList extends ArrayList<Item> {}

    /** Builds a reversed list from the plain list the mapper reads. */
    public static class ReversingConverter extends StdConverter<List<Item>, ConvertedList> {
        @Override
        public ConvertedList convert(List<Item> value) {
            List<Item> read = new ArrayList<>(value);
            Collections.reverse(read);
            ConvertedList list = new ConvertedList();
            list.addAll(read);
            return list;
        }
    }

    /** A list bound by a type-level converter that reverses its input. */
    @JsonDeserialize(converter = ReversingConverter.class)
    public static class ConvertedList extends ArrayList<Item> {}

    /** Fills the name from another member. */
    public static class NameFromOther extends StdConverter<Map<String, Object>, Renamed> {
        @Override
        public Renamed convert(Map<String, Object> value) {
            Renamed renamed = new Renamed();
            renamed.setName((String) value.get("other"));
            return renamed;
        }
    }

    /** A bean bound by a type-level converter that takes its name from another member. */
    @JsonDeserialize(converter = NameFromOther.class)
    public static class Renamed {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Holds one of each. */
    public static class Holder {
        private ReversedList reversed;
        private ConvertedList convertedList;
        private Renamed renamed;

        public ReversedList getReversed() {
            return reversed;
        }

        public void setReversed(ReversedList reversed) {
            this.reversed = reversed;
        }

        public ConvertedList getConvertedList() {
            return convertedList;
        }

        public void setConvertedList(ConvertedList convertedList) {
            this.convertedList = convertedList;
        }

        public Renamed getRenamed() {
            return renamed;
        }

        public void setRenamed(Renamed renamed) {
            this.renamed = renamed;
        }
    }

    private static String resolve(PropertyPath.Segment... segments) {
        return JakartaPathPointers.resolver(MAPPER, null)
                .toPointer(new PropertyPath(List.of(segments)), Holder.class, null);
    }

    @Test
    @DisplayName("a list with a type-level deserializer stops at the list, not at a moved position")
    void listWithTypeLevelDeserializerStopsAtTheList() throws IOException {
        // Given: a document whose second element is the one a reversing deserializer puts first
        String document = "{\"reversed\":[{\"name\":\"valid\"},{\"name\":\"\"}]}";
        Holder bound = MAPPER.readValue(document, Holder.class);

        // When: the violation reported at position 0 resolves
        String pointer =
                resolve(new Property("reversed", Holder.class), new Index(0), new Property("name", Item.class));

        // Then: the data moved, so the pointer stops at the list
        assertAll(
                () -> assertEquals("", bound.getReversed().get(0).getName(), "the mapper moved the element"),
                () -> assertEquals("/reversed", pointer));
    }

    @Test
    @DisplayName("a list with a type-level converter stops at the list, not at a moved position")
    void listWithTypeLevelConverterStopsAtTheList() throws IOException {
        // Given: a document whose second element is the one a reversing converter puts first
        String document = "{\"convertedList\":[{\"name\":\"valid\"},{\"name\":\"\"}]}";
        Holder bound = MAPPER.readValue(document, Holder.class);

        // When: the violation reported at position 0 resolves
        String pointer =
                resolve(new Property("convertedList", Holder.class), new Index(0), new Property("name", Item.class));

        // Then: the data moved, so the pointer stops at the list
        assertAll(
                () -> assertEquals("", bound.getConvertedList().get(0).getName(), "the mapper moved the element"),
                () -> assertEquals("/convertedList", pointer));
    }

    @Test
    @DisplayName("a bean with a type-level converter stops at its holder, not at a member it did not read")
    void beanWithTypeLevelConverterStopsAtItsHolder() throws IOException {
        // Given: a document whose name is valid but whose other member feeds the bound name
        String document = "{\"renamed\":{\"name\":\"valid\",\"other\":\"\"}}";
        Holder bound = MAPPER.readValue(document, Holder.class);

        // When: the violation reported on the bound name resolves
        String pointer = resolve(new Property("renamed", Holder.class), new Property("name", Renamed.class));

        // Then: the pointer stops at the property holding the converted bean
        assertAll(
                () -> assertNotNull(bound.getRenamed()),
                () -> assertEquals("", bound.getRenamed().getName(), "the converter filled name from other"),
                () -> assertEquals("/renamed", pointer));
    }
}
