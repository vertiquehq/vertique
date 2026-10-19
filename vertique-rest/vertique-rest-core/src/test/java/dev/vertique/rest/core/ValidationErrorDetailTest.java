// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationErrorDetail}.
 *
 * <p>Verifies the record's exact members, the {@code pointer} and {@code parameter} members, the
 * either-or rule, the factories, the withers, and the JSON read and write behavior.
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

        // Then: exact member sets, so no location, type or args member appears
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
    @DisplayName("the record exposes exactly pointer, parameter, detail, location, type and args")
    void recordExposesExactlyTheFinalMembers() throws Exception {
        // Given: the record's components and an entry with every member set except parameter
        Map<String, Object> args = Map.of("max", 5);
        ValidationErrorDetail entry = new ValidationErrorDetail("/name", null, "too long", "body", "size", args);

        // When
        List<String> names = Arrays.stream(ValidationErrorDetail.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        Set<String> keys = json(entry).fieldNames();
        Constructor<ValidationErrorDetail> canonical = ValidationErrorDetail.class.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, String.class, Map.class);

        // Then: the components in declared order
        assertEquals(List.of("pointer", "parameter", "detail", "location", "type", "args"), names);

        // And: the serialized key set, without parameter
        assertEquals(Set.of("pointer", "detail", "location", "type", "args"), keys);

        // And: the canonical constructor is the creator Jackson reads with
        assertNotNull(canonical.getAnnotation(JsonCreator.class));

        // And: each wither returns an entry equal in every other component
        assertEquals(
                new ValidationErrorDetail("/name", null, "too long", "query", "size", args),
                entry.withLocation("query"));
        assertEquals(
                new ValidationErrorDetail("/name", null, "too long", "body", "other", args), entry.withType("other"));
        assertEquals(
                new ValidationErrorDetail("/name", null, "too long", "body", "size", Map.of("max", 9)),
                entry.withArgs(Map.of("max", 9)));
    }

    @Test
    @DisplayName("the canonical constructor takes all six components in declared order")
    void canonicalConstructorCarriesEveryComponent() {
        Map<String, Object> args = Map.of("min", 1);

        ValidationErrorDetail detail = new ValidationErrorDetail("/a", null, "d", "body", "required", args);

        assertEquals("/a", detail.pointer());
        assertNull(detail.parameter());
        assertEquals("d", detail.detail());
        assertEquals("body", detail.location());
        assertEquals("required", detail.type());
        assertEquals(args, detail.args());
    }

    @Test
    @DisplayName("an entry with both pointer and parameter is rejected")
    void rejectsAnEntryWithBothPointerAndParameter() {
        // Given / When / Then: both present
        assertThrows(IllegalArgumentException.class, () -> new ValidationErrorDetail("/a", "a", "d", null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ValidationErrorDetail("", "a", "d", "body", "type", Map.of()));

        // Then: only one of them succeeds
        ValidationErrorDetail onlyPointer = new ValidationErrorDetail("/a", null, "d", null, null, null);
        ValidationErrorDetail onlyParameter = new ValidationErrorDetail(null, "a", "d", null, null, null);
        ValidationErrorDetail neither = new ValidationErrorDetail(null, null, "d", null, null, null);
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
        assertThrows(IllegalArgumentException.class, () -> new ValidationErrorDetail("", "a", "d", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ValidationErrorDetail("/a", "", "d", null, null, null));
    }

    @Test
    @DisplayName("an entry with location, type and args serializes every member that is set")
    void serializesLocationTypeAndArgs() throws JsonProcessingException {
        Map<String, Object> args = Map.of("max", 5);

        ValidationErrorDetail detail = new ValidationErrorDetail("/name", null, "too long", "body", "size", args);

        assertEquals(
                new JsonObject()
                        .put("pointer", "/name")
                        .put("detail", "too long")
                        .put("location", "body")
                        .put("type", "size")
                        .put("args", new JsonObject().put("max", 5)),
                json(detail));
    }

    @Test
    @DisplayName("the pointer and parameter factories leave location, type and args null")
    void factoriesLeaveOtherMembersNull() {
        ValidationErrorDetail pointer = ValidationErrorDetail.ofPointer("/a", "d");
        ValidationErrorDetail parameter = ValidationErrorDetail.ofParameter("a", "d");

        assertEquals("/a", pointer.pointer());
        assertNull(pointer.parameter());
        assertEquals("d", pointer.detail());
        assertNull(pointer.location());
        assertNull(pointer.type());
        assertNull(pointer.args());

        assertNull(parameter.pointer());
        assertEquals("a", parameter.parameter());
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
        assertEquals("d", result.detail());
        assertEquals("body", result.location());
        assertEquals("required", result.type());
        assertEquals(Map.of("min", 1), result.args());
        assertEquals(ValidationErrorDetail.ofPointer("/a", "d"), read);
        assertThrows(
                JsonProcessingException.class, () -> read("{\"pointer\":\"/a\",\"parameter\":\"a\",\"detail\":\"d\"}"));
    }

    @Test
    @DisplayName("withers in any order keep pointer and parameter")
    void withersPreserveTheLocationMembers() {
        ValidationErrorDetail pointer = ValidationErrorDetail.ofPointer("", "d");
        ValidationErrorDetail parameter = ValidationErrorDetail.ofParameter("page", "d");
        ValidationErrorDetail neither = new ValidationErrorDetail(null, null, "d", null, null, null);
        Map<String, Object> args = Map.of("k", "v");

        ValidationErrorDetail p = pointer.withArgs(args).withType("t").withLocation("l");
        ValidationErrorDetail q = parameter.withType("t").withArgs(args).withLocation("l");
        ValidationErrorDetail r = neither.withLocation("l").withArgs(args).withType("t");

        assertEquals("", p.pointer());
        assertNull(p.parameter());
        assertNull(q.pointer());
        assertEquals("page", q.parameter());
        assertNull(r.pointer());
        assertNull(r.parameter());
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
        ValidationErrorDetail full = new ValidationErrorDetail(null, "page", "d", "query", "min", Map.of("min", 1));

        ValidationErrorDetail relocated = full.withLocation("body");
        ValidationErrorDetail retyped = full.withType("max");
        ValidationErrorDetail reargued = full.withArgs(null);

        assertEquals(new ValidationErrorDetail(null, "page", "d", "body", "min", Map.of("min", 1)), relocated);
        assertEquals(new ValidationErrorDetail(null, "page", "d", "query", "max", Map.of("min", 1)), retyped);
        assertEquals(new ValidationErrorDetail(null, "page", "d", "query", "min", null), reargued);
    }

    @Test
    @DisplayName("a body naming only the earlier path member reads into an entry without a location member")
    void ignoresTheEarlierPathMemberOnRead() throws JsonProcessingException {
        ValidationErrorDetail read = read("{\"path\":\"/a\",\"detail\":\"d\"}");

        assertNull(read.pointer());
        assertNull(read.parameter());
        assertEquals("d", read.detail());
        assertEquals(new ValidationErrorDetail(null, null, "d", null, null, null), read);
    }

    @Test
    @DisplayName("a body with every member reads into the matching components")
    void readsEveryMember() throws JsonProcessingException {
        ValidationErrorDetail read = read("{\"parameter\":\"page\",\"detail\":\"d\",\"location\":\"query\","
                + "\"type\":\"min\",\"args\":{\"min\":1}}");

        assertEquals(new ValidationErrorDetail(null, "page", "d", "query", "min", Map.of("min", 1)), read);
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
