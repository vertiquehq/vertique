// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultResponseSerializer}.
 *
 * <p>Verifies entity encoding dispatch: null entity, Buffer, String, byte[], and JSON fallback,
 * including default and explicit Content-Type handling, the no-encoder-matches 500 path,
 * and {@link RequestInterceptor#onSerialize} invocation.
 *
 * <p>Note: status code and response headers are set by {@link ResponsePipeline} before
 * the serializer is called. HEAD responses and empty-body responses (204, 304, 412) are handled
 * upstream and do not reach the serializer in normal operation.
 */
class DefaultResponseSerializerTest {

    private static final List<ResponseBodyEncoder> DEFAULT_ENCODERS = List.of(
            new BufferBodyEncoder(),
            new ByteArrayBodyEncoder(),
            new StringBodyEncoder(),
            new ReadStreamBodyEncoder(),
            new JsonBodyEncoder());

    private DefaultResponseSerializer serializer;
    private RoutingContext ctx;
    private HttpServerResponse httpResponse;
    private MultiMap headers;

    @BeforeEach
    void setUp() {
        serializer = new DefaultResponseSerializer(List.of(), DEFAULT_ENCODERS);

        ctx = mock(RoutingContext.class);
        httpResponse = mock(HttpServerResponse.class);

        var httpRequest = mock(HttpServerRequest.class);
        when(httpRequest.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(httpRequest);
        when(ctx.response()).thenReturn(httpResponse);
        when(httpResponse.setStatusCode(anyInt())).thenReturn(httpResponse);
        headers = MultiMap.caseInsensitiveMultiMap();
        when(httpResponse.headers()).thenReturn(headers);
        when(httpResponse.putHeader(anyString(), anyString())).thenAnswer(inv -> {
            headers.add((String) inv.getArgument(0), (String) inv.getArgument(1));
            return httpResponse;
        });
    }

    // --- Null entity (safety-net path) ---

    @Test
    @DisplayName("Should end with no body when serialize() is called with a null entity")
    void shouldEndWithNoBodyForNullEntity() {
        Response response = Response.noContent().build();

        serializer.serialize(ctx, response);

        verify(httpResponse).end();
    }

    // --- String entity ---

    @Test
    @DisplayName("Should write String entity as-is with default text/plain Content-Type")
    void shouldWriteStringAsIs() {
        Response response = Response.ok("pong").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "text/plain");
        verify(httpResponse).end(Buffer.buffer("pong"));
    }

    // --- byte[] entity ---

    @Test
    @DisplayName("Should write byte[] entity as Buffer with default application/octet-stream Content-Type")
    void shouldWriteByteArrayAsBuffer() {
        byte[] data = new byte[] {1, 2, 3};
        Response response = Response.ok(data).build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "application/octet-stream");
        verify(httpResponse).end(Buffer.buffer(data));
    }

    // --- JSON fallback ---

    @Test
    @DisplayName("Should JSON-encode object entity with default application/json Content-Type")
    void shouldJsonEncodeObjectEntity() {
        Map<String, String> entity = Map.of("k", "v");
        Response response = Response.ok(entity).build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "application/json");
        verify(httpResponse).end(Buffer.buffer(Json.encode(entity)));
    }

    // --- No encoder matches ---

    @Test
    @DisplayName("Should return 500 with ProblemDetail when no encoder matches the entity type")
    void shouldReturn500WhenNoEncoderMatches() {
        DefaultResponseSerializer emptyEncoderSerializer = new DefaultResponseSerializer(List.of(), List.of());
        Response response = Response.ok("data").build();

        emptyEncoderSerializer.serialize(ctx, response);

        verify(httpResponse).setStatusCode(500);
        verify(httpResponse).putHeader("Content-Type", "application/problem+json");
        verify(httpResponse).end(argThat((String s) -> s.contains(String.class.getName())));
    }

    // --- Mismatched Content-Type ---

    @Test
    @DisplayName("Should return 500 when explicit Content-Type does not match any encoder")
    void shouldReturn500ForMismatchedContentType() {
        Map<String, String> entity = Map.of("k", "v");
        // Pre-set Content-Type on headers (simulating what the handler does)
        headers.add("Content-Type", "application/xml");
        Response response = Response.ok(entity).type("application/xml").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).setStatusCode(500);
        verify(httpResponse).putHeader("Content-Type", "application/problem+json");
    }

    // --- Explicit Content-Type preservation ---

    @Test
    @DisplayName("Should preserve explicit Content-Type over encoder default")
    void shouldPreserveExplicitContentTypeOverEncoderDefault() {
        // Pre-set Content-Type on headers (simulating what the handler does)
        headers.add("Content-Type", "text/html");
        Response response = Response.ok("data").type("text/html").build();

        serializer.serialize(ctx, response);

        verify(httpResponse, never()).putHeader("Content-Type", "text/plain");
        verify(httpResponse).end(Buffer.buffer("data"));
    }

    // --- onSerialize hook ---

    @Test
    @DisplayName("Should invoke onSerialize hook with non-null SerializedBody for non-null entity")
    void shouldInvokeOnSerializeWithSerializedBody() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer hookSerializer = new DefaultResponseSerializer(List.of(hook), DEFAULT_ENCODERS);
        Response response = Response.ok("payload").build();

        hookSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), any(Response.class), any(SerializedBody.class));
    }

    @Test
    @DisplayName("Should invoke onSerialize hook with 500 Response when no encoder matches")
    void shouldInvokeOnSerializeWhenNoEncoderMatches() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer emptyEncoderSerializer = new DefaultResponseSerializer(List.of(hook), List.of());
        Response response = Response.ok("data").build();

        emptyEncoderSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), argThat((Response r) -> r.getStatus() == 500), any(SerializedBody.class));
    }

    @Test
    @DisplayName("Should invoke onSerialize hook with null body for null entity")
    void shouldInvokeOnSerializeWithNullBodyForNullEntity() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer hookSerializer = new DefaultResponseSerializer(List.of(hook), DEFAULT_ENCODERS);
        Response response = Response.noContent().build();

        hookSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), any(Response.class), isNull());
    }

    // --- GET body encoding ---

    @Test
    @DisplayName("Should write body normally for GET request")
    void shouldWriteBodyForGetRequest() {
        Response response = Response.ok("hello").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).end(Buffer.buffer("hello"));
    }
}
