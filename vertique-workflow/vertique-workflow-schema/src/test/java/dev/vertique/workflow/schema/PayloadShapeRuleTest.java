// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.schema.fixtures.Payloads;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the object-or-void rule: which canonical documents describe an object at their root, which do not, and
 * the wording of the registration failure.
 */
class PayloadShapeRuleTest {

    private static final PayloadShapeRule.Context CONTEXT =
            new PayloadShapeRule.Context("d", 1L, "s", "note", "some.Type");

    // --- Contract proofs ---

    static Stream<Arguments> objectShapedRoots() {
        return Stream.of(
                Arguments.of("plain record", payloadDocument(Payloads.PlainRecord.class)),
                Arguments.of("map of string to object", payloadDocument(Documents.mapOfStringToObject())),
                Arguments.of(
                        "record with a required aliased property", payloadDocument(Payloads.RequiredAliasRecord.class)),
                Arguments.of(
                        "record with an optional aliased property",
                        payloadDocument(Payloads.OptionalAliasRecord.class)),
                Arguments.of("object reached through allOf and a local reference", new JsonObject("""
                    {"allOf": [{"$ref": "#/$defs/Base"}], "$defs": {"Base": {"type": "object"}}}
                    """)),
                Arguments.of("JsonObject by definition", payloadDocument(JsonObject.class)),
                Arguments.of("ObjectNode by definition", payloadDocument(ObjectNode.class)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objectShapedRoots")
    @DisplayName("object-shaped roots are accepted through the conjunctive closure")
    void acceptsObjectShapedRootsThroughTheConjunctiveClosure(String label, JsonObject document) {
        // Given: a document the generator (or the bypass) produced for an object-shaped payload type

        // When / Then: the rule accepts it
        assertThatCode(() -> PayloadShapeRule.check(document, CONTEXT)).doesNotThrowAnyException();
    }

    static Stream<Arguments> notObjectShapedRoots() {
        return Stream.of(
                Arguments.of("String", String.class, "java.lang.String", "string"),
                Arguments.of("int", int.class, "int", "integer"),
                Arguments.of("List of records", Documents.listOfPlainRecords(), "java.util.List", "array"),
                Arguments.of(
                        "record serialized as a value", Payloads.JsonValueRecord.class, valueRecordName(), "string"),
                Arguments.of("polymorphic base", Payloads.SealedShape.class, sealedShapeName(), "none"),
                Arguments.of("JsonArray", JsonArray.class, JsonArray.class.getName(), "none"),
                Arguments.of("Object", Object.class, "java.lang.Object", "none"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notObjectShapedRoots")
    @DisplayName("scalar, array, value and polymorphic roots are rejected with the frozen wording")
    void rejectsScalarArrayValueAndPolymorphicRoots(String label, Type type, String typeName, String rootType) {
        // Given: the generator's document for a type that is not object-shaped
        JsonObject document = Documents.of(type);
        PayloadShapeRule.Context context = new PayloadShapeRule.Context("d", 1L, "s", "note", typeName);

        // When / Then: the rule fails registration naming the definition, step, decision, type and root type
        assertThatThrownBy(() -> PayloadShapeRule.check(document, context))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'd' v1 step 's' decision 'note' payload type '" + typeName
                        + "' is not object-shaped (root type " + rootType + "); wrap the value in a record");
    }

    // --- Fixture shapes the contract relies on ---

    @Test
    @DisplayName("a required alias is stated as a presence rule beside the root type")
    void requiredAliasRecordCarriesAPresenceRuleBesideTheRootType() {
        // Given: the generator's document for a record with a required aliased property
        JsonObject document = Documents.of(Payloads.RequiredAliasRecord.class);

        // Then: the root declares both the object type and an anyOf, so accepting it proves anyOf is not a rejection
        assertThat(document.getString("type")).isEqualTo("object");
        assertThat(document.getJsonArray("anyOf")).isNotEmpty();
    }

    @Test
    @DisplayName("document objects are object-shaped by definition, before generation, with a fixed document")
    void documentObjectsAreObjectShapedByDefinitionWithAFixedDocument() {
        // Given / When / Then: exactly the two document-object types bypass generation
        assertThat(PayloadShapeRule.isObjectByDefinition(JsonObject.class.getName()))
                .isTrue();
        assertThat(PayloadShapeRule.isObjectByDefinition(ObjectNode.class.getName()))
                .isTrue();
        assertThat(PayloadShapeRule.isObjectByDefinition(JsonArray.class.getName()))
                .isFalse();
        assertThat(PayloadShapeRule.isObjectByDefinition(ArrayNode.class.getName()))
                .isFalse();
        assertThat(PayloadShapeRule.isObjectByDefinition("com.fasterxml.jackson.databind.JsonNode"))
                .isFalse();
        assertThat(PayloadShapeRule.isObjectByDefinition(Object.class.getName()))
                .isFalse();
        assertThat(PayloadShapeRule.isObjectByDefinition(Map.class.getName())).isFalse();

        // And: the fixed document is the bare object type, which the rule accepts
        assertThat(PayloadShapeRule.objectByDefinitionDocument()).isEqualTo(new JsonObject().put("type", "object"));
        assertThatCode(() -> PayloadShapeRule.check(PayloadShapeRule.objectByDefinitionDocument(), CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("each call returns its own fixed document")
    void theFixedDocumentIsNotShared() {
        // Given: one caller mutates the document it received
        JsonObject first = PayloadShapeRule.objectByDefinitionDocument();
        first.put("additionalProperties", false);

        // Then: the next caller still receives the bare object type
        assertThat(PayloadShapeRule.objectByDefinitionDocument()).isEqualTo(new JsonObject().put("type", "object"));
    }

    // --- Closure semantics on hand-built documents ---

    static Stream<Arguments> acceptedDocuments() {
        return Stream.of(
                Arguments.of("type object", "{\"type\": \"object\"}"),
                Arguments.of("type as a one element array", "{\"type\": [\"object\"]}"),
                Arguments.of(
                        "type object beside anyOf",
                        "{\"type\": \"object\", \"anyOf\": [{\"required\": [\"a\"]}, {\"required\": [\"b\"]}]}"),
                Arguments.of("allOf branch", "{\"allOf\": [{\"type\": \"object\"}]}"),
                Arguments.of("nested allOf branches", "{\"allOf\": [{\"allOf\": [{\"type\": \"object\"}]}]}"),
                Arguments.of(
                        "local reference to a definition",
                        "{\"$ref\": \"#/$defs/Base\", \"$defs\": {\"Base\": {\"type\": \"object\"}}}"),
                Arguments.of(
                        "reference from an allOf branch to a reference",
                        "{\"allOf\": [{\"$ref\": \"#/$defs/A\"}], \"$defs\": {\"A\": {\"$ref\": \"#/$defs/B\"},"
                                + " \"B\": {\"type\": \"object\"}}}"),
                Arguments.of("reference to the root", "{\"type\": \"object\", \"allOf\": [{\"$ref\": \"#\"}]}"),
                Arguments.of(
                        "object declared by two closure nodes",
                        "{\"type\": \"object\", \"allOf\": [{\"type\": \"object\"}]}"),
                Arguments.of("chain of sixteen references", referenceChain(16).encode()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedDocuments")
    @DisplayName("the conjunctive closure accepts a document that declares object and nothing else")
    void acceptsDocumentsWhoseClosureDeclaresObject(String label, String json) {
        // Given: a hand-built document
        JsonObject document = new JsonObject(json);

        // When / Then: accepted
        assertThatCode(() -> PayloadShapeRule.check(document, CONTEXT)).doesNotThrowAnyException();
    }

    static Stream<Arguments> rejectedDocuments() {
        return Stream.of(
                Arguments.of("empty schema", "{}", "none"),
                Arguments.of("array type", "{\"type\": \"array\"}", "array"),
                Arguments.of("anyOf alone", "{\"anyOf\": [{\"type\": \"object\"}]}", "none"),
                Arguments.of("oneOf alone", "{\"oneOf\": [{\"type\": \"object\"}, {\"type\": \"object\"}]}", "none"),
                Arguments.of(
                        "object that may also be null", "{\"type\": [\"object\", \"null\"]}", "[\"object\",\"null\"]"),
                Arguments.of(
                        "allOf branch with a different type",
                        "{\"type\": \"object\", \"allOf\": [{\"type\": \"string\"}]}",
                        "string"),
                Arguments.of(
                        "reference to a different type",
                        "{\"type\": \"object\", \"allOf\": [{\"$ref\": \"#/$defs/S\"}], \"$defs\": {\"S\": {\"type\":"
                                + " \"string\"}}}",
                        "string"),
                Arguments.of(
                        "the first declared type that is not object is named",
                        "{\"type\": \"array\", \"allOf\": [{\"type\": \"string\"}]}",
                        "array"),
                Arguments.of("reference to a missing definition", "{\"$ref\": \"#/$defs/Missing\"}", "none"),
                Arguments.of("reference to another document", "{\"$ref\": \"https://example.com/x.json\"}", "none"),
                Arguments.of(
                        "reference cycle",
                        "{\"$ref\": \"#/$defs/A\", \"$defs\": {\"A\": {\"$ref\": \"#/$defs/B\"}, \"B\": {\"$ref\":"
                                + " \"#/$defs/A\"}}}",
                        "none"),
                Arguments.of("chain of seventeen references", referenceChain(17).encode(), "none"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedDocuments")
    @DisplayName("the conjunctive closure rejects a document that does not declare object")
    void rejectsDocumentsWhoseClosureDoesNotDeclareObject(String label, String json, String rootType) {
        // Given: a hand-built document
        JsonObject document = new JsonObject(json);

        // When / Then: rejected, naming the first declared type that is not object, else none
        assertThatThrownBy(() -> PayloadShapeRule.check(document, CONTEXT))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 's' decision 'note' payload type 'some.Type' is not object-shaped"
                        + " (root type " + rootType + "); wrap the value in a record");
    }

    @Test
    @DisplayName("a start payload is named by the start payload wording")
    void startPayloadContextUsesTheStartPayloadWording() {
        // Given: a context without a step and decision
        PayloadShapeRule.Context start = new PayloadShapeRule.Context("d", 7L, null, null, "java.lang.String");

        // When / Then: the message names the start payload instead of a step and decision
        assertThatThrownBy(() -> PayloadShapeRule.check(Documents.of(String.class), start))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'd' v7 start payload payload type 'java.lang.String' is not"
                        + " object-shaped (root type string); wrap the value in a record");
    }

    // --- Helpers ---

    /** What the registration path hands to the rule: the fixed document for document objects, else the generator's. */
    private static JsonObject payloadDocument(Type type) {
        String name = type instanceof Class<?> c ? c.getName() : type.getTypeName();
        return PayloadShapeRule.isObjectByDefinition(name)
                ? PayloadShapeRule.objectByDefinitionDocument()
                : Documents.of(type);
    }

    /** A root that is only a reference, followed through {@code hops} references to an object definition. */
    private static JsonObject referenceChain(int hops) {
        JsonObject definitions = new JsonObject();
        for (int index = 1; index < hops; index++) {
            definitions.put("h" + index, new JsonObject().put("$ref", "#/$defs/h" + (index + 1)));
        }
        definitions.put("h" + hops, new JsonObject().put("type", "object"));
        return new JsonObject().put("$ref", "#/$defs/h1").put("$defs", definitions);
    }

    private static String valueRecordName() {
        return Payloads.JsonValueRecord.class.getName();
    }

    private static String sealedShapeName() {
        return Payloads.SealedShape.class.getName();
    }
}
