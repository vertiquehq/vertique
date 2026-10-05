// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins Jakarta REST's absent-scalar contract for {@link ParameterExtractor}: a primitive without
 * {@code @DefaultValue} binds its Java language default; a reference type binds {@code null};
 * {@code @DefaultValue} still wins when present.
 */
class AbsentPrimitiveParamBindTest {

    /** Resource fixture with scalar query parameters of mixed primitive / boxed types. */
    static final class ScalarResource {
        @SuppressWarnings("unused")
        public String limit(int limit) {
            return String.valueOf(limit);
        }

        @SuppressWarnings("unused")
        public String flag(boolean flag) {
            return String.valueOf(flag);
        }

        @SuppressWarnings("unused")
        public String offset(long offset) {
            return String.valueOf(offset);
        }

        @SuppressWarnings("unused")
        public String boxed(Integer boxed) {
            return String.valueOf(boxed);
        }

        @SuppressWarnings("unused")
        public String withDefault(int page) {
            return String.valueOf(page);
        }
    }

    private static ResourceMethodMeta metaFor(Method method, ResourceMethodMeta.ParamMeta param) {
        return new ResourceMethodMeta(
                new ScalarResource(),
                method,
                method.getName(),
                "GET",
                "/things",
                List.of(param),
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
    }

    private static ParameterExtractor extractorFor(ResourceMethodMeta meta) {
        List<RequestBodyDecoder> decoders = List.of(new JsonRequestBodyDecoder());
        return new ParameterExtractor(meta, decoders, new RestContextResolution(Set.of()));
    }

    /** Minimal {@link BoundRequest} whose query map holds the supplied entries (empty = absent). */
    private static BoundRequest boundQuery(Map<String, RequestValue> query) {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return query;
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
                return RequestValue.of(null);
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }

    @Nested
    @DisplayName("reflective extractArguments (BoundRequest scalar path)")
    class ReflectivePath {

        @Test
        @DisplayName("absent int without @DefaultValue binds 0")
        void absentIntBindsZero() throws Exception {
            Method method = ScalarResource.class.getMethod("limit", int.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("limit", ResourceMethodMeta.ParamSource.QUERY, int.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of()));

            assertEquals(1, args.length);
            assertEquals(0, args[0]);
        }

        @Test
        @DisplayName("absent boolean without @DefaultValue binds false")
        void absentBooleanBindsFalse() throws Exception {
            Method method = ScalarResource.class.getMethod("flag", boolean.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("flag", ResourceMethodMeta.ParamSource.QUERY, boolean.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of()));

            assertEquals(1, args.length);
            assertEquals(false, args[0]);
        }

        @Test
        @DisplayName("absent long without @DefaultValue binds 0L")
        void absentLongBindsZero() throws Exception {
            Method method = ScalarResource.class.getMethod("offset", long.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("offset", ResourceMethodMeta.ParamSource.QUERY, long.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of()));

            assertEquals(1, args.length);
            assertEquals(0L, args[0]);
        }

        @Test
        @DisplayName("absent Integer without @DefaultValue binds null")
        void absentIntegerBindsNull() throws Exception {
            Method method = ScalarResource.class.getMethod("boxed", Integer.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("boxed", ResourceMethodMeta.ParamSource.QUERY, Integer.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of()));

            assertEquals(1, args.length);
            assertNull(args[0]);
        }

        @Test
        @DisplayName("present int still binds the submitted value")
        void presentIntBindsValue() throws Exception {
            Method method = ScalarResource.class.getMethod("limit", int.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("limit", ResourceMethodMeta.ParamSource.QUERY, int.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of("limit", RequestValue.of(42))));

            assertEquals(1, args.length);
            assertEquals(42, args[0]);
        }

        @Test
        @DisplayName("@DefaultValue wins over the Java primitive default when the param is absent")
        void defaultValueWinsWhenAbsent() throws Exception {
            Method method = ScalarResource.class.getMethod("withDefault", int.class);
            ResourceMethodMeta.ParamMeta param = new ResourceMethodMeta.ParamMeta(
                    "page", ResourceMethodMeta.ParamSource.QUERY, int.class, null, null, "10", (Annotation[]) null);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            Object[] args = extractor.extractArguments(null, boundQuery(Map.of()));

            assertEquals(1, args.length);
            assertEquals(10, args[0]);
        }
    }

    @Nested
    @DisplayName("generated path (extractScalarParam via ParameterExtractorBackedSupport)")
    class GeneratedPath {

        @Test
        @DisplayName("absent int without @DefaultValue binds 0 through extractScalarParam")
        void absentIntViaExtractScalarParam() throws Exception {
            Method method = ScalarResource.class.getMethod("limit", int.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("limit", ResourceMethodMeta.ParamSource.QUERY, int.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));
            ParameterExtractorBackedSupport support = new ParameterExtractorBackedSupport(extractor);

            Object result = support.extractScalarParam(param, EffectiveInputPolicies.NONE, boundQuery(Map.of()));

            assertEquals(0, result);
        }

        @Test
        @DisplayName("absent Integer without @DefaultValue binds null through extractScalarParam")
        void absentIntegerViaExtractScalarParam() throws Exception {
            Method method = ScalarResource.class.getMethod("boxed", Integer.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("boxed", ResourceMethodMeta.ParamSource.QUERY, Integer.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));
            ParameterExtractorBackedSupport support = new ParameterExtractorBackedSupport(extractor);

            Object result = support.extractScalarParam(param, EffectiveInputPolicies.NONE, boundQuery(Map.of()));

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("text @FormParam scalar absence")
    class FormParamPath {

        @Test
        @DisplayName("absent int @FormParam without @DefaultValue binds 0")
        void absentFormIntBindsZero() throws Exception {
            Method method = ScalarResource.class.getMethod("limit", int.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("limit", ResourceMethodMeta.ParamSource.FORM, int.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(ctx.request()).thenReturn(request);
            when(request.getFormAttribute("limit")).thenReturn(null);

            Object result = extractor.extractFormParam(param, EffectiveInputPolicies.NONE, ctx);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("absent Integer @FormParam without @DefaultValue binds null")
        void absentFormIntegerBindsNull() throws Exception {
            Method method = ScalarResource.class.getMethod("boxed", Integer.class);
            ResourceMethodMeta.ParamMeta param =
                    new ResourceMethodMeta.ParamMeta("boxed", ResourceMethodMeta.ParamSource.FORM, Integer.class);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(ctx.request()).thenReturn(request);
            when(request.getFormAttribute("boxed")).thenReturn(null);

            Object result = extractor.extractFormParam(param, EffectiveInputPolicies.NONE, ctx);

            assertNull(result);
        }

        @Test
        @DisplayName("@DefaultValue wins for an absent text @FormParam int")
        void formDefaultValueWins() throws Exception {
            Method method = ScalarResource.class.getMethod("withDefault", int.class);
            ResourceMethodMeta.ParamMeta param = new ResourceMethodMeta.ParamMeta(
                    "page", ResourceMethodMeta.ParamSource.FORM, int.class, null, null, "7", (Annotation[]) null);
            ParameterExtractor extractor = extractorFor(metaFor(method, param));

            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(ctx.request()).thenReturn(request);
            when(request.getFormAttribute("page")).thenReturn(null);

            Object result = extractor.extractFormParam(param, EffectiveInputPolicies.NONE, ctx);

            assertEquals(7, result);
        }
    }
}
