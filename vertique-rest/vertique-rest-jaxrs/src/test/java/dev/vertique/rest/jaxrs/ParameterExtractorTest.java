// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-3 migration test for {@link ParameterExtractor}'s reflective dispatch path, verifying that
 * the BODY parameter is now read from a {@link BoundRequest}'s {@link BoundRequest#body()}
 * ({@link RequestValue}) rather than from a Vert.x {@code ValidatedRequest} (FR-024).
 */
class ParameterExtractorTest {

    /** Simple POJO record the BODY param deserializes into. */
    record MyDto(String name) {}

    /** Resource fixture whose method has a single body parameter. */
    static final class BodyResource {
        @SuppressWarnings("unused")
        public String create(MyDto dto) {
            return dto.name();
        }
    }

    @Test
    @DisplayName("extractArguments reads the BODY param from BoundRequest.body() and deserializes the DTO")
    void parameterExtractorReadsFromRequestValueBodyField() throws Exception {
        Method create = BodyResource.class.getMethod("create", MyDto.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new BodyResource(),
                create,
                "create",
                "POST",
                "/dto",
                List.of(new ResourceMethodMeta.ParamMeta("dto", ResourceMethodMeta.ParamSource.BODY, MyDto.class)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        List<RequestBodyDecoder> decoders = List.of(new JsonRequestBodyDecoder());
        ParameterExtractor extractor = new ParameterExtractor(meta, decoders, new RestContextResolution(Set.of()));

        // Routing context: Content-Type drives decoder selection; body is bound via BoundRequest.
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");

        BoundRequest boundRequest = stubBoundRequest(RequestValue.of(new JsonObject().put("name", "Bob")));

        Object[] args = extractor.extractArguments(ctx, boundRequest);

        assertEquals(1, args.length);
        assertInstanceOf(MyDto.class, args[0]);
        assertEquals("Bob", ((MyDto) args[0]).name());
    }

    /** Minimal {@link BoundRequest} stub exposing the supplied body and empty parameter maps. */
    private static BoundRequest stubBoundRequest(RequestValue body) {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> headers() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return Map.of();
            }

            @Override
            public RequestValue body() {
                return body;
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }
}
