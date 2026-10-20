// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the path form and the nullable path of {@link ViolationDetail}. */
class ViolationDetailTest {

    @Test
    @DisplayName("the four-argument constructor and of(...) state a Java property path")
    void shortFormsStateAJavaPropertyPath() {
        assertEquals(
                ViolationPathForm.JAVA_PROPERTY_PATH,
                new ViolationDetail("items[0].name", "blank", "required", null).form());
        assertEquals(
                ViolationPathForm.JAVA_PROPERTY_PATH,
                ViolationDetail.of("name", "blank").form());
    }

    @Test
    @DisplayName("a null form in the canonical constructor becomes the Java property path form")
    void nullFormBecomesJavaPropertyPath() {
        ViolationDetail detail = new ViolationDetail("name", "blank", null, null, null);

        assertEquals(ViolationPathForm.JAVA_PROPERTY_PATH, detail.form());
    }

    @Test
    @DisplayName("atPointer states a pointer form and keeps every member")
    void atPointerStatesAPointer() {
        ViolationDetail detail = ViolationDetail.atPointer("/items/0/name", "blank", "required", Map.of("min", 1));

        assertEquals(ViolationPathForm.JSON_POINTER, detail.form());
        assertEquals("/items/0/name", detail.path());
        assertEquals("blank", detail.message());
        assertEquals("required", detail.type());
        assertEquals(Map.of("min", 1), detail.args());
    }

    @Test
    @DisplayName("atPointer keeps the empty pointer, the whole document")
    void atPointerKeepsTheEmptyPointer() {
        assertEquals("", ViolationDetail.atPointer("", "bad", null, null).path());
    }

    @Test
    @DisplayName("unlocated has no path, no args and carries the message and the rule identifier as type")
    void unlocatedHasNoPath() {
        ViolationDetail detail = ViolationDetail.unlocated("credit limit exceeded", "RULE001");

        assertNull(detail.path());
        assertNull(detail.args());
        assertEquals("credit limit exceeded", detail.message());
        assertEquals("RULE001", detail.type());
    }

    @Test
    @DisplayName("the form is not part of the JSON form of a detail and a null path is omitted")
    void formIsNotSerialized() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String pointer = mapper.writeValueAsString(ViolationDetail.atPointer("/a", "bad", "format", null));
        String unlocated = mapper.writeValueAsString(ViolationDetail.unlocated("bad", "RULE001"));

        assertEquals("{\"path\":\"/a\",\"message\":\"bad\",\"type\":\"format\"}", pointer);
        assertFalse(unlocated.contains("path"), unlocated);
        assertFalse(unlocated.contains("form"), unlocated);
    }
}
