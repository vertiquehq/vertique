// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonRequestBodyDecoder}.
 *
 * <p>Verifies that the decoder accepts {@code null}, JSON-compatible, and case-insensitive
 * content types while rejecting non-JSON types. Also verifies correct dispatch by target type
 * ({@link JsonObject}, {@link JsonArray}, {@link String}, POJO, {@code List<T>}) and
 * null-body handling.
 */
class JsonRequestBodyDecoderTest {

    private final JsonRequestBodyDecoder decoder = new JsonRequestBodyDecoder();

    /** {@link ParameterizedType} representing {@code List<SamplePojo>}. */
    private static final Type LIST_OF_SAMPLE_POJO = new ParameterizedType() {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {SamplePojo.class};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    };

    // --- canDecode ---

    @Test
    @DisplayName("Should accept null content type (fallback decoder)")
    void shouldAcceptNullContentType() {
        assertTrue(decoder.canDecode(Object.class, null));
    }

    @Test
    @DisplayName("Should accept application/json content type")
    void shouldAcceptApplicationJson() {
        assertTrue(decoder.canDecode(Object.class, "application/json"));
    }

    @Test
    @DisplayName("Should accept application/vnd.api+json content type")
    void shouldAcceptVendorJson() {
        assertTrue(decoder.canDecode(Object.class, "application/vnd.api+json"));
    }

    @Test
    @DisplayName("Should accept APPLICATION/JSON case-insensitively")
    void shouldAcceptUpperCaseJson() {
        assertTrue(decoder.canDecode(Object.class, "APPLICATION/JSON"));
    }

    @Test
    @DisplayName("Should reject text/plain content type")
    void shouldRejectTextPlain() {
        assertFalse(decoder.canDecode(Object.class, "text/plain"));
    }

    // --- decode ---

    @Test
    @DisplayName("Should return JsonObject from body when target type is JsonObject")
    void shouldReturnJsonObjectForJsonObjectTarget() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonObject expected = new JsonObject().put("key", "value");
        RequestValue body = RequestValue.of(expected);

        Object result = decoder.decode(ctx, body, JsonObject.class, null);

        assertSame(expected, result);
    }

    @Test
    @DisplayName("Should return String from body when target type is String")
    void shouldReturnStringForStringTarget() {
        RoutingContext ctx = mock(RoutingContext.class);
        RequestValue body = RequestValue.of("{\"k\":\"v\"}");

        Object result = decoder.decode(ctx, body, String.class, null);

        assertEquals("{\"k\":\"v\"}", result);
    }

    @Test
    @DisplayName("Should return null when json body is null for POJO target")
    void shouldReturnNullWhenJsonBodyIsNull() {
        RoutingContext ctx = mock(RoutingContext.class);
        RequestValue body = RequestValue.of(null);

        Object result = decoder.decode(ctx, body, SamplePojo.class, null);

        assertNull(result);
    }

    @Test
    @DisplayName("Should map JsonObject to POJO for non-String non-JsonObject target")
    void shouldMapJsonObjectToPojo() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonObject json = new JsonObject().put("name", "Alice");
        RequestValue body = RequestValue.of(json);

        Object result = decoder.decode(ctx, body, SamplePojo.class, null);

        assertInstanceOf(SamplePojo.class, result);
        assertEquals("Alice", ((SamplePojo) result).name());
    }

    @Test
    @DisplayName("Should return JsonArray from body when target type is JsonArray")
    void shouldDecodeJsonArrayTargetType() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray expected =
                new JsonArray().add(new JsonObject().put("name", "a")).add(new JsonObject().put("name", "b"));
        RequestValue body = RequestValue.of(expected);

        Object result = decoder.decode(ctx, body, JsonArray.class, null);

        assertSame(expected, result);
    }

    @Test
    @DisplayName("Should map JsonArray elements to List<SamplePojo> when generic type is provided")
    void shouldDecodeListWithGenericType() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray jsonArray =
                new JsonArray().add(new JsonObject().put("name", "Alice")).add(new JsonObject().put("name", "Bob"));
        RequestValue body = RequestValue.of(jsonArray);

        Object result = decoder.decode(ctx, body, List.class, LIST_OF_SAMPLE_POJO);

        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertInstanceOf(SamplePojo.class, list.get(0));
        assertEquals("Alice", ((SamplePojo) list.get(0)).name());
    }

    @Test
    @DisplayName("Should return raw List<Object> when target is List with no generic type")
    void shouldDecodeRawListAsListOfObjects() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray jsonArray = new JsonArray().add(new JsonObject().put("name", "test"));
        RequestValue body = RequestValue.of(jsonArray);

        Object result = decoder.decode(ctx, body, List.class, null);

        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(1, list.size());
    }

    // --- scalar coercion ---

    @Test
    @DisplayName("Should coerce integer elements to Long for List<Long> target")
    void shouldCoerceScalarElementsToLong() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray jsonArray = new JsonArray().add(1).add(2).add(3);
        RequestValue body = RequestValue.of(jsonArray);

        Type listOfLong = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {Long.class};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };

        Object result = decoder.decode(ctx, body, List.class, listOfLong);

        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertInstanceOf(Long.class, list.get(0));
        assertEquals(1L, list.get(0));
    }

    // --- Set<T> ---

    @Test
    @DisplayName("Should decode JSON array to Set<SamplePojo>")
    void shouldDecodeSetWithGenericType() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray jsonArray =
                new JsonArray().add(new JsonObject().put("name", "Alice")).add(new JsonObject().put("name", "Bob"));
        RequestValue body = RequestValue.of(jsonArray);

        Type setOfSamplePojo = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {SamplePojo.class};
            }

            @Override
            public Type getRawType() {
                return Set.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };

        Object result = decoder.decode(ctx, body, Set.class, setOfSamplePojo);

        assertInstanceOf(Set.class, result);
        Set<?> set = (Set<?>) result;
        assertEquals(2, set.size());
    }

    // --- T[] array ---

    @Test
    @DisplayName("Should decode JSON array to SamplePojo[]")
    void shouldDecodeArray() {
        RoutingContext ctx = mock(RoutingContext.class);
        JsonArray jsonArray =
                new JsonArray().add(new JsonObject().put("name", "Alice")).add(new JsonObject().put("name", "Bob"));
        RequestValue body = RequestValue.of(jsonArray);

        Object result = decoder.decode(ctx, body, SamplePojo[].class, null);

        assertInstanceOf(SamplePojo[].class, result);
        SamplePojo[] array = (SamplePojo[]) result;
        assertEquals(2, array.length);
        assertEquals("Alice", array[0].name());
        assertEquals("Bob", array[1].name());
    }

    // --- end-to-end through DefaultBoundRequest ---

    @Test
    @DisplayName("Should decode List<SamplePojo> from a DefaultBoundRequest-bound JSON array body")
    void shouldDecodeListFromBoundRequestArrayBody() {
        Buffer raw = Buffer.buffer("[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        // The real bind path must produce a JsonArray (not a Buffer) so the decoder can map it.
        assertNotNull(bound.body().getJsonArray(), "bound array body must be a JsonArray");

        Object result = decoder.decode(ctx, bound.body(), List.class, LIST_OF_SAMPLE_POJO);

        assertInstanceOf(List.class, result);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertInstanceOf(SamplePojo.class, list.get(0));
        assertEquals("Alice", ((SamplePojo) list.get(0)).name());
        assertEquals("Bob", ((SamplePojo) list.get(1)).name());
    }

    // --- DefaultBoundRequest mock helpers ---

    /** Builds a content-type-aware {@link RoutingContext} carrying the given raw body buffer. */
    private static RoutingContext bodyContext(String contentType, Buffer rawBody) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(request.getHeader("Content-Type")).thenReturn(contentType);

        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(rawBody);
        when(body.asJsonObject()).thenAnswer(inv -> {
            Object decoded = Json.decodeValue(rawBody);
            return decoded instanceof JsonObject jo ? jo : null;
        });
        when(body.asJsonArray()).thenAnswer(inv -> {
            Object decoded = Json.decodeValue(rawBody);
            return decoded instanceof JsonArray ja ? ja : null;
        });
        when(ctx.body()).thenReturn(body);
        return ctx;
    }

    /** Builds a no-param POST operation descriptor for body binding. */
    private static JaxRsOperationDescriptor bodyOp() {
        return new JaxRsOperationDescriptor() {
            @Override
            public String operationId() {
                return "op";
            }

            @Override
            public String httpMethod() {
                return "POST";
            }

            @Override
            public String routeTemplate() {
                return "/things";
            }

            @Override
            public List<String> consumes() {
                return List.of();
            }

            @Override
            public List<String> produces() {
                return List.of();
            }

            @Override
            public SecurityPolicy securityPolicy() {
                return new SecurityPolicy.None();
            }

            @Override
            public List<SecurityRequirementSet> securityRequirementSets() {
                return List.of();
            }

            @Override
            public List<Annotation> methodAnnotations() {
                return List.of();
            }

            @Override
            public List<Annotation> classAnnotations() {
                return List.of();
            }

            @Override
            public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
                return Optional.empty();
            }

            @Override
            public List<ParamDescriptor> parameters() {
                return List.of();
            }

            @Override
            public List<FilePartDescriptor> fileParts() {
                return List.of();
            }

            @Override
            public Optional<BodyDescriptor> body() {
                return Optional.of(new BodyDescriptor(List.class, LIST_OF_SAMPLE_POJO, List.of()));
            }
        };
    }

    // --- helper types ---

    /** Simple POJO record used for JSON mapping tests. */
    record SamplePojo(String name) {}
}
