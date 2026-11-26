// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Property;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The document check reads a list position only from a list it can index directly. */
@DisplayName("JakartaPathPointers document lists")
class JakartaPathPointersDocumentListTest {

    public record Named(String name) {}

    public record Bag(List<Named> items) {}

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final PropertyPath SECOND_NAME = new PropertyPath(
            List.of(new Property("items", Bag.class), new Index(1), new Property("name", Named.class)));

    private static String resolve(Object document) {
        return JakartaPathPointers.resolver(MAPPER, JacksonFieldNameResolver.forMapper(MAPPER))
                .toPointer(SECOND_NAME, Bag.class, document);
    }

    @Test
    @DisplayName("a document list backed by an array resolves the position")
    void arrayBackedListResolvesThePosition() {
        // Given: a checked document whose list supports positional reads
        Map<String, Object> document = Map.of("items", List.of(Map.of("name", "a"), Map.of("name", "b")));

        // When: the second element's member resolves
        String pointer = resolve(document);

        // Then: it is named at its position
        assertEquals("/items/1/name", pointer);
    }

    @Test
    @DisplayName("a document list that cannot be read by position stops the pointer at the list")
    void linkedListStopsAtTheList() {
        // Given: the same document held in a linked list
        Map<String, Object> document =
                Map.of("items", new LinkedList<>(List.of(Map.of("name", "a"), Map.of("name", "b"))));

        // When: the second element's member resolves
        String pointer = resolve(document);

        // Then: the position is not guessed
        assertEquals("/items", pointer);
    }
}
