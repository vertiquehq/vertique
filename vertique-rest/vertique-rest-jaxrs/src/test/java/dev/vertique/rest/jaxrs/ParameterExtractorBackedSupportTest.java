// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.context.RestContextResolver;
import dev.vertique.rest.core.context.RestContextUnavailableException;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParameterExtractorBackedSupport}. The adapter is a thin pass-through
 * over {@link ParameterExtractor}'s package-private policy-accepting helpers; each test verifies
 * the corresponding delegation actually fires with the expected arguments and the result is
 * returned unchanged.
 *
 * <p>After slice 4 the generated-runtime SPI is fully neutral: {@code extractScalarParam} and
 * {@code materializeBean} take a {@link BoundRequest} (not a Vert.x {@code ValidatedRequest}), and
 * {@code deserializeBody} reads the bound request from the routing-context stash under
 * {@link BoundRequest#KEY_META_DATA_BOUND_REQUEST}.
 *
 * <p>Mockito 5.x natively mocks {@link ParameterExtractor} despite it being {@code final}.
 */
class ParameterExtractorBackedSupportTest {

    private final ParameterExtractor delegate = mock(ParameterExtractor.class);
    private final ParameterExtractorBackedSupport support = new ParameterExtractorBackedSupport(delegate);

    private static ResourceMethodMeta.ParamMeta paramMeta(ResourceMethodMeta.ParamSource source, Class<?> type) {
        return new ResourceMethodMeta.ParamMeta("p", source, type, null, null, null, new Annotation[0]);
    }

    /**
     * Builds a {@link BoundRequest} stub exposing the supplied parameter maps and body; any argument
     * may be {@code null} for an empty map / null body.
     */
    private static BoundRequest boundRequest(
            @Nullable Map<String, RequestValue> path,
            @Nullable Map<String, RequestValue> query,
            @Nullable Map<String, RequestValue> headers,
            @Nullable Map<String, RequestValue> cookies,
            @Nullable RequestValue body) {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return path != null ? path : Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return query != null ? query : Map.of();
            }

            @Override
            public Map<String, RequestValue> headers() {
                return headers != null ? headers : Map.of();
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return cookies != null ? cookies : Map.of();
            }

            @Override
            public RequestValue body() {
                return body != null ? body : RequestValue.of(null);
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }

    @Nested
    @DisplayName("delegates each helper to ParameterExtractor with passed-through arguments")
    class Delegation {

        @Test
        @DisplayName("extractScalarParam → ParameterExtractor.extractScalarParam(meta, policies, boundRequest)")
        void extractScalarParam() {
            ResourceMethodMeta.ParamMeta meta = paramMeta(ResourceMethodMeta.ParamSource.QUERY, String.class);
            EffectiveInputPolicies policies = EffectiveInputPolicies.NONE;
            BoundRequest req = boundRequest(null, null, null, null, null);
            when(delegate.extractScalarParam(meta, policies, req)).thenReturn("value");

            Object result = support.extractScalarParam(meta, policies, req);

            assertSame("value", result);
            verify(delegate, times(1)).extractScalarParam(meta, policies, req);
        }

        @Test
        @DisplayName("extractFormParam → ParameterExtractor.extractFormParam")
        void extractFormParam() {
            ResourceMethodMeta.ParamMeta meta = paramMeta(ResourceMethodMeta.ParamSource.FORM, String.class);
            EffectiveInputPolicies policies = EffectiveInputPolicies.NONE;
            RoutingContext ctx = mock(RoutingContext.class);
            when(delegate.extractFormParam(meta, policies, ctx)).thenReturn("form-value");

            Object result = support.extractFormParam(meta, policies, ctx);

            assertSame("form-value", result);
            verify(delegate, times(1)).extractFormParam(meta, policies, ctx);
        }

        @Test
        @DisplayName("extractFileUploads → returns immutable copy of ctx.fileUploads()")
        @SuppressWarnings("unchecked")
        void extractFileUploads() {
            RoutingContext ctx = mock(RoutingContext.class);
            FileUpload fu = mock(FileUpload.class);
            when(ctx.fileUploads()).thenReturn(List.of(fu));

            Object result = support.extractFileUploads(ctx);

            assertNotNull(result);
            List<FileUpload> uploads = (List<FileUpload>) result;
            assertSame(fu, uploads.get(0));
        }

        @Test
        @DisplayName("extractEntityParts → ParameterExtractor.extractAllEntityParts")
        void extractEntityParts() {
            ResourceMethodMeta.ParamMeta meta = paramMeta(ResourceMethodMeta.ParamSource.ENTITY_PARTS, List.class);
            RoutingContext ctx = mock(RoutingContext.class);
            List<jakarta.ws.rs.core.EntityPart> parts = List.of();
            when(delegate.extractAllEntityParts(ctx)).thenReturn(parts);

            Object result = support.extractEntityParts(meta, ctx);

            assertSame(parts, result);
            verify(delegate, times(1)).extractAllEntityParts(ctx);
        }

        @Test
        @DisplayName("deserializeBody → reads the stashed BoundRequest body into the extractor")
        void deserializeBody_withBoundRequest() {
            ResourceMethodMeta.ParamMeta meta = paramMeta(ResourceMethodMeta.ParamSource.BODY, String.class);
            EffectiveInputPolicies policies = EffectiveInputPolicies.NONE;
            RoutingContext ctx = mock(RoutingContext.class);
            io.vertx.core.json.JsonObject rawBody = new io.vertx.core.json.JsonObject().put("k", "v");
            RequestValue bodyValue = RequestValue.of(rawBody);
            BoundRequest req = boundRequest(null, null, null, null, bodyValue);
            when(ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST)).thenReturn(req);
            when(delegate.deserializeBody(eq(bodyValue), eq(String.class), any(), eq(ctx), eq(policies)))
                    .thenReturn("body-value");

            Object result = support.deserializeBody(meta, policies, ctx);

            assertSame("body-value", result);
            verify(delegate, times(1)).deserializeBody(eq(bodyValue), eq(String.class), any(), eq(ctx), eq(policies));
        }

        @Test
        @DisplayName("deserializeBody → bridges a null-valued RequestValue when BoundRequest absent in ctx")
        void deserializeBody_withoutBoundRequest() {
            ResourceMethodMeta.ParamMeta meta = paramMeta(ResourceMethodMeta.ParamSource.BODY, String.class);
            EffectiveInputPolicies policies = EffectiveInputPolicies.NONE;
            RoutingContext ctx = mock(RoutingContext.class);
            when(ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST)).thenReturn(null);

            support.deserializeBody(meta, policies, ctx);

            verify(delegate, times(1))
                    .deserializeBody(
                            argThat((RequestValue rv) -> rv != null && rv.isNull()),
                            eq(String.class),
                            any(),
                            eq(ctx),
                            eq(policies));
        }

        @Test
        @DisplayName("materializeBean → ParameterExtractor.materializeBean with all arguments forwarded")
        void materializeBean() {
            BeanParamFieldMeta[] fields = new BeanParamFieldMeta[0];
            EffectiveInputPolicies routePolicies = EffectiveInputPolicies.NONE;
            BoundRequest req = boundRequest(null, null, null, null, null);
            RoutingContext ctx = mock(RoutingContext.class);
            Object beanInstance = new Object();
            when(delegate.materializeBean(fields, routePolicies, req, ctx, Object.class))
                    .thenReturn(beanInstance);

            Object result = support.materializeBean(fields, routePolicies, req, ctx, Object.class);

            assertSame(beanInstance, result);
            verify(delegate, times(1)).materializeBean(fields, routePolicies, req, ctx, Object.class);
        }

        @Test
        @DisplayName("resolveContext → ParameterExtractor.resolveContext with all arguments forwarded")
        void resolveContext() {
            RoutingContext ctx = mock(RoutingContext.class);
            Object contextValue = new Object();
            when(delegate.resolveContext(Object.class, ctx, "MyResource", "myMethod"))
                    .thenReturn(contextValue);

            Object result = support.resolveContext(Object.class, ctx, "MyResource", "myMethod");

            assertSame(contextValue, result);
            verify(delegate, times(1)).resolveContext(Object.class, ctx, "MyResource", "myMethod");
        }
    }

    // --- BoundRequest-backed extraction via a real ParameterExtractor ---

    @Nested
    @DisplayName("GeneratedJaxRsSupport extraction from a BoundRequest (real ParameterExtractor)")
    class BoundRequestExtraction {

        /** Bean fixture with a single @QueryParam-annotated field. */
        static final class NameBean {
            @jakarta.ws.rs.QueryParam("name")
            public String name;
        }

        /**
         * Builds a {@link ParameterExtractorBackedSupport} over a real {@link ParameterExtractor}
         * wired with the given resource method meta, no decoders, and an empty resolver chain.
         */
        private static ParameterExtractorBackedSupport realSupport(ResourceMethodMeta meta) {
            ParameterExtractor extractor = new ParameterExtractor(meta, List.of(), new RestContextResolution(Set.of()));
            return new ParameterExtractorBackedSupport(extractor);
        }

        /** No-op resource method meta around {@link Object#toString()} with no declared params. */
        private static ResourceMethodMeta noOpMeta() throws Exception {
            return new ResourceMethodMeta(
                    new Object(),
                    Object.class.getMethod("toString"),
                    "op",
                    "GET",
                    "/op",
                    List.of(),
                    Object.class,
                    false,
                    false,
                    new dev.vertique.rest.core.security.SecurityPolicy.None(),
                    new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null);
        }

        @Test
        @DisplayName("extractScalarParam reads a QUERY scalar from the BoundRequest and coerces to int")
        void extractScalarParamFromBoundRequest() throws Exception {
            ParameterExtractorBackedSupport realSupport = realSupport(noOpMeta());
            ResourceMethodMeta.ParamMeta pageMeta =
                    new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class);
            BoundRequest req = boundRequest(null, Map.of("page", RequestValue.of(2)), null, null, null);

            Object result = realSupport.extractScalarParam(pageMeta, EffectiveInputPolicies.NONE, req);

            assertEquals(2, result);
        }

        @Test
        @DisplayName("materializeBean populates a @BeanParam bean from the BoundRequest query map")
        void materializeBeanFromBoundRequest() throws Exception {
            ParameterExtractorBackedSupport realSupport = realSupport(noOpMeta());
            ResourceMethodMeta.ParamMeta fieldMeta = new ResourceMethodMeta.ParamMeta(
                    "name", ResourceMethodMeta.ParamSource.QUERY, String.class, null, null, null, new Annotation[0]);
            BeanParamFieldMeta[] fields = new BeanParamFieldMeta[] {new BeanParamFieldMeta("name", fieldMeta)};
            BoundRequest req = boundRequest(null, Map.of("name", RequestValue.of("Alice")), null, null, null);
            RoutingContext ctx = mock(RoutingContext.class);

            Object bean = realSupport.materializeBean(fields, EffectiveInputPolicies.NONE, req, ctx, NameBean.class);

            assertNotNull(bean);
            assertEquals("Alice", ((NameBean) bean).name);
        }
    }

    // --- resolveContext integration via real RestContextResolution ---

    /**
     * Marker type used as the resolved context value type in these integration-style tests.
     * Declared as a record so it is unambiguous — no other resolver in the test chain will
     * accidentally match it.
     */
    record Marker() {}

    /**
     * Builds a {@link ParameterExtractorBackedSupport} backed by a real {@link ParameterExtractor}
     * wired with the given {@link RestContextResolution}. Uses no security, no decoders, and a
     * no-op {@link ResourceMethodMeta} built around {@link Object#toString()}.
     *
     * @param resolution the resolver chain to wire into the extractor
     * @return a fully wired support instance for integration assertions
     */
    private static ParameterExtractorBackedSupport supportWithResolution(RestContextResolution resolution) {
        try {
            ResourceMethodMeta meta = new ResourceMethodMeta(
                    new Object(),
                    Object.class.getMethod("toString"),
                    "op",
                    "GET",
                    "/op",
                    List.of(),
                    Object.class,
                    false,
                    false,
                    new dev.vertique.rest.core.security.SecurityPolicy.None(),
                    new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null);
            ParameterExtractor extractor = new ParameterExtractor(meta, List.of(), resolution);
            return new ParameterExtractorBackedSupport(extractor);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies that {@link ParameterExtractorBackedSupport#resolveContext} returns the value
     * supplied by a matching {@link RestContextResolver} when the resolver chain contains one
     * that handles the requested type.
     */
    @Test
    @DisplayName("resolveContext returns value from matching resolver in the chain")
    void resolveContext_matchingResolver_returnsValue() {
        Marker expected = new Marker();
        RestContextResolver resolver = new RestContextResolver() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
                if (type == Marker.class) {
                    return Optional.of((T) expected);
                }
                return Optional.empty();
            }
        };

        ParameterExtractorBackedSupport realSupport =
                supportWithResolution(new RestContextResolution(Set.of(resolver)));
        RoutingContext ctx = mock(RoutingContext.class);

        Object result = realSupport.resolveContext(Marker.class, ctx, "MyResource", "myMethod");

        assertSame(expected, result);
    }

    /**
     * Verifies that {@link ParameterExtractorBackedSupport#resolveContext} throws
     * {@link RestContextUnavailableException} when no resolver in the chain handles the requested
     * type (FR-REST-174).
     */
    @Test
    @DisplayName("resolveContext throws RestContextUnavailableException when no resolver matches")
    void resolveContext_noMatchingResolver_throwsUnavailable() {
        // Empty resolver chain — nothing can supply Marker.
        ParameterExtractorBackedSupport realSupport = supportWithResolution(new RestContextResolution(Set.of()));
        RoutingContext ctx = mock(RoutingContext.class);

        assertThrows(
                RestContextUnavailableException.class,
                () -> realSupport.resolveContext(Marker.class, ctx, "MyResource", "myMethod"));
    }
}
