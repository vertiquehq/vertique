// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MediaTypeValidator} against the framework's built-in decoders and
 * encoders, which accept a media type only for particular target types.
 */
class MediaTypeValidatorTest {

    private static final List<RequestBodyDecoder> DECODERS = List.of(
            new JsonRequestBodyDecoder(),
            new TextRequestBodyDecoder(),
            new BinaryRequestBodyDecoder(),
            new FormUrlencodedRequestBodyDecoder());

    private static final List<ResponseBodyEncoder> ENCODERS = List.of(
            new JsonBodyEncoder(), new StringBodyEncoder(), new ByteArrayBodyEncoder(), new BufferBodyEncoder());

    @Test
    @DisplayName("Should accept text/plain and application/octet-stream that built-in codecs support by type")
    void shouldAcceptTypeKeyedBuiltInMediaTypes() {
        ResourceMethodMeta method = method(
                "textAndBinary",
                List.of("text/plain", "application/octet-stream"),
                List.of("text/plain", "application/octet-stream"));

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));
    }

    @Test
    @DisplayName("Should accept JSON media types")
    void shouldAcceptJson() {
        ResourceMethodMeta method = method("json", List.of("application/json"), List.of("application/json"));

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));
    }

    @Test
    @DisplayName("Should still reject a media type no decoder or encoder accepts for any probe type")
    void shouldRejectUnsupportedMediaType() {
        List<ResponseBodyEncoder> jsonOnly = List.of(new JsonBodyEncoder());
        ResourceMethodMeta method =
                method("xmlOp", List.of("application/xml"), List.of("application/pdf", "application/json"));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> MediaTypeValidator.validate(List.of(method), DECODERS, jsonOnly, "STRICT"));

        assertTrue(ex.getMessage().contains("@Consumes(\"application/xml\")"), ex.getMessage());
        assertTrue(ex.getMessage().contains("@Produces(\"application/pdf\")"), ex.getMessage());
        assertFalse(ex.getMessage().contains("@Produces(\"application/json\")"), ex.getMessage());
    }

    @Test
    @DisplayName("Should report every mismatch across methods in STRICT mode, not only the first")
    void shouldReportAllMismatches() {
        List<ResponseBodyEncoder> jsonOnly = List.of(new JsonBodyEncoder());
        ResourceMethodMeta first = method("first", List.of("application/xml"), List.of());
        ResourceMethodMeta second = method("second", List.of(), List.of("application/pdf"));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> MediaTypeValidator.validate(List.of(first, second), DECODERS, jsonOnly, "STRICT"));

        assertTrue(ex.getMessage().contains("Operation 'first'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Operation 'second'"), ex.getMessage());
    }

    @Test
    @DisplayName("Should not throw in WARN mode and skip validation entirely in OFF mode")
    void shouldNotThrowInWarnOrOffMode() {
        ResourceMethodMeta method = method("xmlOp", List.of("application/xml"), List.of("application/pdf"));

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "WARN"));
        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "OFF"));
    }

    @Test
    @DisplayName("Should skip methods without declared media types")
    void shouldSkipMethodsWithoutMediaTypes() {
        ResourceMethodMeta method = mock(ResourceMethodMeta.class);
        when(method.mediaTypes()).thenReturn(null);

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));
    }

    record Widget(String name) {}

    @Test
    @DisplayName("Should report a @Produces type that no built-in encoder writes for the method's real response type")
    void shouldReportUnwritableProducesForTheRealResponseType() {
        ResourceMethodMeta method = method("widget", List.of(), List.of("application/pdf"));
        when(method.responseBodyType()).thenAnswer(invocation -> Widget.class);

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));

        assertTrue(ex.getMessage().contains("@Produces(\"application/pdf\")"), ex.getMessage());
    }

    @Test
    @DisplayName("Should accept a @Produces type an encoder writes for the declared response type")
    void shouldAcceptProducesWrittenForTheDeclaredType() {
        ResourceMethodMeta json = method("json", List.of(), List.of("application/json"));
        when(json.responseBodyType()).thenAnswer(invocation -> Widget.class);
        ResourceMethodMeta text = method("text", List.of(), List.of("text/plain"));
        when(text.responseBodyType()).thenAnswer(invocation -> String.class);
        ResourceMethodMeta binary = method("binary", List.of(), List.of("application/pdf"));
        when(binary.responseBodyType()).thenAnswer(invocation -> byte[].class);

        assertDoesNotThrow(
                () -> MediaTypeValidator.validate(List.of(json, text, binary), DECODERS, ENCODERS, "STRICT"));
    }

    @Test
    @DisplayName("Should name the response type in a @Produces mismatch")
    void shouldNameTheResponseTypeInTheMismatch() {
        ResourceMethodMeta method = method("widget", List.of(), List.of("application/pdf"));
        when(method.responseBodyType()).thenAnswer(invocation -> Widget.class);

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));

        assertTrue(ex.getMessage().contains("for response type " + Widget.class.getTypeName()), ex.getMessage());
    }

    @Test
    @DisplayName("Should keep the permissive probe when the response type is not fixed")
    void shouldProbePermissivelyForAnUnfixedResponseType() {
        ResourceMethodMeta object = method("object", List.of(), List.of("application/pdf"));
        when(object.responseBodyType()).thenAnswer(invocation -> Object.class);
        ResourceMethodMeta response = method("response", List.of(), List.of("application/pdf"));
        when(response.responseBodyType()).thenAnswer(invocation -> jakarta.ws.rs.core.Response.class);
        ResourceMethodMeta voided = method("voided", List.of(), List.of("application/pdf"));
        when(voided.responseBodyType()).thenAnswer(invocation -> Void.class);
        ResourceMethodMeta unknown = method("unknown", List.of(), List.of("application/pdf"));

        assertDoesNotThrow(() ->
                MediaTypeValidator.validate(List.of(object, response, voided, unknown), DECODERS, ENCODERS, "STRICT"));
    }

    @Test
    @DisplayName("Should accept a supertype response type when a value of it could be written by a built-in encoder")
    void shouldProbeRuntimeBodyTypesOfASupertype() {
        ResourceMethodMeta method = method("chars", List.of(), List.of("application/pdf"));
        when(method.responseBodyType()).thenAnswer(invocation -> CharSequence.class);

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "STRICT"));
    }

    @Test
    @DisplayName("Should only log a @Produces mismatch in WARN mode")
    void shouldOnlyWarnOnAProducesMismatch() {
        ResourceMethodMeta method = method("widget", List.of(), List.of("application/pdf"));
        when(method.responseBodyType()).thenAnswer(invocation -> Widget.class);

        assertDoesNotThrow(() -> MediaTypeValidator.validate(List.of(method), DECODERS, ENCODERS, "WARN"));
    }

    private static ResourceMethodMeta method(String operationId, List<String> consumes, List<String> produces) {
        ResourceMethodMeta meta = mock(ResourceMethodMeta.class);
        when(meta.operationId()).thenReturn(operationId);
        when(meta.mediaTypes()).thenReturn(new ResourceMethodMeta.MediaTypes(consumes, produces));
        return meta;
    }
}
