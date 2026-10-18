// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationErrorDetail}.
 *
 * <p>Verifies the {@code pointer} and {@code parameter} members, the either-or rule, the retained
 * {@code path}-based constructor and factory, the withers, and the JSON read and write behavior.
 */
class ValidationErrorDetailTest {

    private static JsonObject json(ValidationErrorDetail detail) throws JsonProcessingException {
        return new JsonObject(VertiqueJson.mapper().writeValueAsString(detail));
    }

    private static ValidationErrorDetail read(String json) throws JsonProcessingException {
        return VertiqueJson.mapper().readValue(json, ValidationErrorDetail.class);
    }

    @Test
    @DisplayName("pointer and parameter are serialized and null members are omitted")
    void serializesPointerAndParameterAndOmitsNulls() throws JsonProcessingException {
        // Given
        ValidationErrorDetail pointer = ValidationErrorDetail.ofPointer("/items/0/name", "must not be blank");
        ValidationErrorDetail parameter = ValidationErrorDetail.ofParameter("page", "too small");
        ValidationErrorDetail wholeDocument = ValidationErrorDetail.ofPointer("", "whole document");

        // When
        JsonObject pointerJson = json(pointer);
        JsonObject parameterJson = json(parameter);
        JsonObject wholeDocumentJson = json(wholeDocument);

        // Then: exact member sets, so no path, location, type or args member appears
        assertEquals(new JsonObject().put("pointer", "/items/0/name").put("detail", "must not be blank"), pointerJson);
        assertEquals(new JsonObject().put("parameter", "page").put("detail", "too small"), parameterJson);
        assertTrue(wholeDocumentJson.containsKey("pointer"));
        assertEquals("", wholeDocumentJson.getString("pointer"));
        assertEquals(new JsonObject().put("pointer", "").put("detail", "whole document"), wholeDocumentJson);
    }

    @Test
    @DisplayName("an empty pointer is serialized as an empty string")
    void serializesTheEmptyPointer() throws JsonProcessingException {
        String serialized = VertiqueJson.mapper().writeValueAsString(ValidationErrorDetail.ofPointer("", "d"));

        assertTrue(serialized.contains("\"pointer\":\"\""), serialized);
    }

    @Test
    @DisplayName("the components are declared in the order pointer, parameter, path, detail, location, type, args")
    void declaresComponentsInContractOrder() {
        List<String> names = Arrays.stream(ValidationErrorDetail.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("pointer", "parameter", "path", "detail", "location", "type", "args"), names);
    }

    @Test
    @DisplayName("the canonical constructor takes all seven components in declared order")
    void canonicalConstructorCarriesEveryComponent() {
        Map<String, Object> args = Map.of("min", 1);

        ValidationErrorDetail detail = new ValidationErrorDetail("/a", null, "legacy", "d", "body", "required", args);

        assertEquals("/a", detail.pointer());
        assertNull(detail.parameter());
        assertEquals("legacy", detail.path());
        assertEquals("d", detail.detail());
        assertEquals("body", detail.location());
        assertEquals("required", detail.type());
        assertEquals(args, detail.args());
    }

    @Test
    @DisplayName("an entry with both pointer and parameter is rejected")
    void rejectsAnEntryWithBothPointerAndParameter() {
        // Given / When / Then: both present
        assertThrows(
                IllegalArgumentException.class,
                () -> new ValidationErrorDetail("/a", "a", null, "d", null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ValidationErrorDetail("", "a", "/legacy", "d", "body", "type", Map.of()));

        // Then: only one of them succeeds
        ValidationErrorDetail onlyPointer = new ValidationErrorDetail("/a", null, null, "d", null, null, null);
        ValidationErrorDetail onlyParameter = new ValidationErrorDetail(null, "a", null, "d", null, null, null);
        ValidationErrorDetail neither = new ValidationErrorDetail(null, null, null, "d", null, null, null);
        assertEquals("/a", onlyPointer.pointer());
        assertNull(onlyPointer.parameter());
        assertNull(onlyParameter.pointer());
        assertEquals("a", onlyParameter.parameter());
        assertNull(neither.pointer());
        assertNull(neither.parameter());
    }

    @Test
    @DisplayName("an empty pointer still counts as present when combined with a parameter")
    void rejectsAnEmptyPointerCombinedWithAParameter() {
        assertThrows(
                IllegalArgumentException.class, () -> new ValidationErrorDetail("", "a", null, "d", null, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new ValidationErrorDetail("/a", "", null, "d", null, null, null));
    }

    @Test
    @DisplayName("the five-argument constructor yields path with null pointer and parameter")
    void fiveArgumentConstructorKeepsPath() throws JsonProcessingException {
        Map<String, Object> args = Map.of("max", 5);

        ValidationErrorDetail detail = new ValidationErrorDetail("/name", "too long", "body", "size", args);

        assertNull(detail.pointer());
        assertNull(detail.parameter());
        assertEquals("/name", detail.path());
        assertEquals("too long", detail.detail());
        assertEquals("body", detail.location());
        assertEquals("size", detail.type());
        assertEquals(args, detail.args());
        assertEquals(
                new JsonObject()
                        .put("path", "/name")
                        .put("detail", "too long")
                        .put("location", "body")
                        .put("type", "size")
                        .put("args", new JsonObject().put("max", 5)),
                json(detail));
    }

    @Test
    @DisplayName("of() yields path with null pointer, parameter, location, type and args")
    void ofKeepsPathAndSerializesAsBefore() throws JsonProcessingException {
        ValidationErrorDetail detail = ValidationErrorDetail.of("/x", "bad");

        assertNull(detail.pointer());
        assertNull(detail.parameter());
        assertEquals("/x", detail.path());
        assertEquals("bad", detail.detail());
        assertNull(detail.location());
        assertNull(detail.type());
        assertNull(detail.args());
        assertEquals(new JsonObject().put("path", "/x").put("detail", "bad"), json(detail));
    }

    @Test
    @DisplayName("the pointer and parameter factories leave path, location, type and args null")
    void factoriesLeaveOtherMembersNull() {
        ValidationErrorDetail pointer = ValidationErrorDetail.ofPointer("/a", "d");
        ValidationErrorDetail parameter = ValidationErrorDetail.ofParameter("a", "d");

        assertEquals("/a", pointer.pointer());
        assertNull(pointer.parameter());
        assertNull(pointer.path());
        assertEquals("d", pointer.detail());
        assertNull(pointer.location());
        assertNull(pointer.type());
        assertNull(pointer.args());

        assertNull(parameter.pointer());
        assertEquals("a", parameter.parameter());
        assertNull(parameter.path());
        assertEquals("d", parameter.detail());
        assertNull(parameter.location());
        assertNull(parameter.type());
        assertNull(parameter.args());
    }

    @Test
    @DisplayName("withers carry every member and unknown members are ignored when reading")
    void withersCarryEveryMemberAndUnknownMembersAreIgnored() throws JsonProcessingException {
        // Given
        ValidationErrorDetail base = ValidationErrorDetail.ofPointer("/a", "d");
        String futureJson = "{\"pointer\":\"/a\",\"detail\":\"d\",\"future\":1}";

        // When
        ValidationErrorDetail result =
                base.withLocation("body").withType("required").withArgs(Map.of("min", 1));
        ValidationErrorDetail read = read(futureJson);

        // Then
        assertEquals("/a", result.pointer());
        assertNull(result.parameter());
        assertNull(result.path());
        assertEquals("d", result.detail());
        assertEquals("body", result.location());
        assertEquals("required", result.type());
        assertEquals(Map.of("min", 1), result.args());
        assertEquals(ValidationErrorDetail.ofPointer("/a", "d"), read);
        assertThrows(
                JsonProcessingException.class, () -> read("{\"pointer\":\"/a\",\"parameter\":\"a\",\"detail\":\"d\"}"));
    }

    @Test
    @DisplayName("withers in any order keep pointer, parameter and path")
    void withersPreserveTheLocationMembers() {
        ValidationErrorDetail pointer = ValidationErrorDetail.ofPointer("", "d");
        ValidationErrorDetail parameter = ValidationErrorDetail.ofParameter("page", "d");
        ValidationErrorDetail path = ValidationErrorDetail.of("/legacy", "d");
        Map<String, Object> args = Map.of("k", "v");

        ValidationErrorDetail p = pointer.withArgs(args).withType("t").withLocation("l");
        ValidationErrorDetail q = parameter.withType("t").withArgs(args).withLocation("l");
        ValidationErrorDetail r = path.withLocation("l").withArgs(args).withType("t");

        assertEquals("", p.pointer());
        assertNull(p.parameter());
        assertNull(p.path());
        assertNull(q.pointer());
        assertEquals("page", q.parameter());
        assertNull(q.path());
        assertNull(r.pointer());
        assertNull(r.parameter());
        assertEquals("/legacy", r.path());
        for (ValidationErrorDetail each : List.of(p, q, r)) {
            assertEquals("d", each.detail());
            assertEquals("l", each.location());
            assertEquals("t", each.type());
            assertEquals(args, each.args());
        }
    }

    @Test
    @DisplayName("each wither replaces only its own member")
    void eachWitherReplacesOnlyItsOwnMember() {
        ValidationErrorDetail full =
                new ValidationErrorDetail(null, "page", "legacy", "d", "query", "min", Map.of("min", 1));

        ValidationErrorDetail relocated = full.withLocation("body");
        ValidationErrorDetail retyped = full.withType("max");
        ValidationErrorDetail reargued = full.withArgs(null);

        assertEquals(
                new ValidationErrorDetail(null, "page", "legacy", "d", "body", "min", Map.of("min", 1)), relocated);
        assertEquals(new ValidationErrorDetail(null, "page", "legacy", "d", "query", "max", Map.of("min", 1)), retyped);
        assertEquals(new ValidationErrorDetail(null, "page", "legacy", "d", "query", "min", null), reargued);
    }

    @Test
    @DisplayName("a body with path and detail still reads into a path-only entry")
    void readsAPathOnlyBody() throws JsonProcessingException {
        ValidationErrorDetail read = read("{\"path\":\"/a\",\"detail\":\"d\"}");

        assertNull(read.pointer());
        assertNull(read.parameter());
        assertEquals("/a", read.path());
        assertEquals("d", read.detail());
        assertEquals(ValidationErrorDetail.of("/a", "d"), read);
    }

    @Test
    @DisplayName("a body with every member reads into the matching components")
    void readsEveryMember() throws JsonProcessingException {
        ValidationErrorDetail read =
                read("{\"parameter\":\"page\",\"path\":\"legacy\",\"detail\":\"d\",\"location\":\"query\","
                        + "\"type\":\"min\",\"args\":{\"min\":1}}");

        assertEquals(new ValidationErrorDetail(null, "page", "legacy", "d", "query", "min", Map.of("min", 1)), read);
    }

    @Test
    @DisplayName("an empty pointer reads back as an empty pointer")
    void readsTheEmptyPointer() throws JsonProcessingException {
        ValidationErrorDetail read = read("{\"pointer\":\"\",\"detail\":\"d\"}");

        assertEquals("", read.pointer());
        assertEquals(ValidationErrorDetail.ofPointer("", "d"), read);
    }

    @Test
    @DisplayName("a body carrying both pointer and parameter fails to read because of the either-or rule")
    void rejectsAMalformedBodyOnRead() {
        JsonProcessingException failure = assertThrows(
                JsonProcessingException.class,
                () -> read("{\"pointer\":\"\",\"parameter\":\"a\",\"detail\":\"d\",\"location\":\"body\"}"));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
