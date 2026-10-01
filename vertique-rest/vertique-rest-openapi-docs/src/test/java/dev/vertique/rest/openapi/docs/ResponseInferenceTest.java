// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.openapi.docs.ResponseInference.Inference;
import dev.vertique.rest.openapi.docs.ResponseInference.Row;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.CustomProducers;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.Dto;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.GenericResource;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.Item;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.ItemResource;
import dev.vertique.rest.openapi.docs.fixture.responses.shapes.ShapeResource;
import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that every return shape of a resource method classifies into the response the
 * documentation publishes for it: the status, the media types in published order, and the type the
 * body is described from, with a type only for a JSON entity.
 *
 * <p>Each row builds the response facts of a fixture method as the runtime captures them, with the
 * producer binding of {@code Custom} as the only other input.
 */
class ResponseInferenceTest {

    private static final String JSON = "application/json";
    private static final String XML = "application/xml";
    private static final String TEXT = "text/plain";
    private static final String VND_JSON = "application/vnd.acme+json";
    private static final String EVENT_STREAM = "text/event-stream";

    /** A field whose generic type is {@code List<Dto>}, the fixed expected type of a list row. */
    @SuppressWarnings("unused")
    private static final List<Dto> LIST_OF_DTO = null;

    /** A field whose generic type is {@code List<Item>}, the fixed expected type of a list row. */
    @SuppressWarnings("unused")
    private static final List<Item> LIST_OF_ITEM = null;

    /** The expected classification of one row. */
    private record Expected(Row row, String status, List<String> mediaTypes, Type outputType) {}

    private static Expected noContent() {
        return new Expected(Row.NO_CONTENT, "204", List.of(), null);
    }

    private static Expected json(List<String> mediaTypes, Type outputType) {
        return new Expected(Row.JSON_ENTITY, "200", mediaTypes, outputType);
    }

    private static Expected rawText(List<String> mediaTypes) {
        return new Expected(Row.RAW_TEXT, "200", mediaTypes, null);
    }

    private static Expected runtime() {
        return new Expected(Row.RUNTIME, "default", List.of(), null);
    }

    private static Type fieldType(String name) {
        try {
            return ResponseInferenceTest.class.getDeclaredField(name).getGenericType();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Arguments row(
            String label, Class<?> resourceClass, String method, List<String> produces, Expected expected) {
        return Arguments.of(label, resourceClass, method, produces, expected);
    }

    static Stream<Arguments> rows() {
        Class<?> shapes = ShapeResource.class;
        Type listOfDto = fieldType("LIST_OF_DTO");
        Type listOfItem = fieldType("LIST_OF_ITEM");
        return Stream.of(
                row("void", shapes, "voidReturn", List.of(), noContent()),
                row("Void", shapes, "voidObject", List.of(), noContent()),
                row("Future<Void>", shapes, "futureOfVoid", List.of(), noContent()),
                row("Future<Dto>, no produces", shapes, "futureOfDto", List.of(), json(List.of(JSON), Dto.class)),
                row("Dto, no produces", shapes, "dto", List.of(), json(List.of(JSON), Dto.class)),
                row("Dto, json and xml", shapes, "dto", List.of(JSON, XML), json(List.of(JSON), Dto.class)),
                row("Dto, vnd.acme+json", shapes, "dto", List.of(VND_JSON), json(List.of(VND_JSON), Dto.class)),
                row(
                        "Future<List<Dto>>, no produces",
                        shapes,
                        "futureOfListOfDto",
                        List.of(),
                        json(List.of(JSON), listOfDto)),
                row("String, no produces", shapes, "stringReturn", List.of(), rawText(List.of(JSON))),
                row("String, text/plain", shapes, "stringReturn", List.of(TEXT), rawText(List.of(TEXT))),
                row("Future<String>, text/plain", shapes, "futureOfString", List.of(TEXT), rawText(List.of(TEXT))),
                row(
                        "String, json and text/plain",
                        shapes,
                        "stringReturn",
                        List.of(JSON, TEXT),
                        rawText(List.of(JSON, TEXT))),
                row(
                        "ItemResource: inherited T find()",
                        ItemResource.class,
                        "find",
                        List.of(),
                        json(List.of(JSON), Item.class)),
                row(
                        "ItemResource: inherited Future<List<T>> list()",
                        ItemResource.class,
                        "list",
                        List.of(),
                        json(List.of(JSON), listOfItem)),
                row("method-level <T> T", shapes, "any", List.of(), runtime()),
                row("GenericResource<T>: T get()", GenericResource.class, "get", List.of(), runtime()),
                row("List<?>", shapes, "listOfWildcard", List.of(), runtime()),
                row("Response", shapes, "response", List.of(), runtime()),
                row("Future<Response>", shapes, "futureOfResponse", List.of(), runtime()),
                row("CompletionStage<Dto>", shapes, "completionStageOfDto", List.of(), runtime()),
                row("Optional<Dto>", shapes, "optionalOfDto", List.of(), runtime()),
                row("ReadStream<Buffer>", shapes, "readStreamOfBuffer", List.of(), runtime()),
                row("Dto, text/event-stream", shapes, "dto", List.of(EVENT_STREAM), runtime()),
                row("byte[]", shapes, "bytes", List.of(), runtime()),
                row("Buffer", shapes, "buffer", List.of(), runtime()),
                row("Custom, producer-bound", shapes, "custom", List.of(), runtime()),
                row("SubCustom, subclass of a producer-bound type", shapes, "subCustom", List.of(), runtime()),
                row("Dto, only text/plain", shapes, "dto", List.of(TEXT), runtime()),
                row("raw Future", shapes, "rawFuture", List.of(), runtime()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("classifies every return shape by its status, media types, and output type")
    void classifiesEveryReturnShape(
            String label, Class<?> resourceClass, String method, List<String> produces, Expected expected) {
        // Given the response facts of the method, as the runtime captures them
        ResponseShape shape = ResponseDocuments.shape(resourceClass, method, produces);

        // When the return shape is classified with the producer binding of Custom
        Inference inference = ResponseInference.classify(shape, CustomProducers.bindings());

        // Then the row, status, media types, and type to describe the body from are exactly expected
        assertEquals(expected.row(), inference.row(), label + ": row");
        assertEquals(expected.status(), inference.status(), label + ": status");
        assertEquals(expected.mediaTypes(), inference.mediaTypes(), label + ": media types");
        if (expected.outputType() == null) {
            assertNull(inference.outputType(), label + ": no type is handed to the schema generator");
        } else {
            assertEquals(expected.outputType(), inference.outputType(), label + ": output type");
        }
    }
}
