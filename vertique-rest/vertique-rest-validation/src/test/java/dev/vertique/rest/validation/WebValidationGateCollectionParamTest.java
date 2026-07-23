// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the {@code web-validation} gate validates <em>every</em> value of a multi-value parameter
 * (a {@code List<T>}/{@code Set<T>}/array param whose descriptor carries a non-null
 * {@code componentType}) against the parameter's item schema, not only the first value. A request
 * carrying one in-range value and one out-of-range value must be rejected with the violation
 * referencing the offending element — proving the collection path is engaged end-to-end once
 * {@code ResourceScanner.resolveComponentType} recognizes Set/array shapes and propagates
 * {@code componentType} to the descriptor.
 */
class WebValidationGateCollectionParamTest {

    private final WebValidationStrategy strategy =
            new WebValidationStrategy(JaxRsConfig.builder().build());

    // --- Descriptor + context stubs ---

    private static JaxRsOperationDescriptor op(List<ParamDescriptor> params) {
        return new JaxRsOperationDescriptor() {
            @Override
            public String operationId() {
                return "op";
            }

            @Override
            public String httpMethod() {
                return "GET";
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
                return params;
            }

            @Override
            public List<FilePartDescriptor> fileParts() {
                return List.of();
            }

            @Override
            public Optional<BodyDescriptor> body() {
                return Optional.empty();
            }
        };
    }

    private static ParamDescriptor collectionParam(
            String name, ParamLocation location, Class<?> type, Class<?> componentType) {
        return new ParamDescriptor(name, location, type, componentType, null, null, List.of());
    }

    private static RoutingContext mockContext(MultiMap query) {
        RoutingContext ctx = mock(RoutingContext.class);
        var request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(java.util.Map.of());
        when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(ctx.body()).thenReturn(null);
        when(ctx.get(anyString())).thenReturn(null);
        when(ctx.put(anyString(), any())).thenReturn(ctx);
        return ctx;
    }

    private static MultiMap multiQuery(String name, String... values) {
        MultiMap q = MultiMap.caseInsensitiveMultiMap();
        for (String value : values) {
            q.add(name, value);
        }
        return q;
    }

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        return assertInstanceOf(RestValidationException.class, captor.getValue());
    }

    private static boolean references(RestValidationException ex, String token) {
        for (ValidationErrorDetail e : ex.errors()) {
            String path = e.path() == null ? "" : e.path();
            String detail = e.detail() == null ? "" : e.detail();
            if (path.contains(token) || detail.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // --- Tests ---

    @Test
    @DisplayName("List<Integer> @QueryParam: an out-of-range element is rejected (all values validated)")
    void webValidationGateRejectsInvalidCollectionElement() {
        ParamDescriptor ids = collectionParam("ids", ParamLocation.QUERY, List.class, Integer.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "ids",
                        new JsonObject()
                                .put("type", "array")
                                .put(
                                        "items",
                                        new JsonObject().put("type", "integer").put("maximum", 100)))
                .build();
        Handler<RoutingContext> gate =
                strategy.gateFor(op(List.of(ids)), schemas).orElseThrow();

        // ?ids=1&ids=500 — the second value exceeds maximum:100; the first is in range.
        RoutingContext bad = mockContext(multiQuery("ids", "1", "500"));
        gate.handle(bad);

        RestValidationException ex = captureFailure(bad);
        assertTrue(references(ex, "ids") || references(ex, "100"), "violation must reference the bad element");
        verify(bad, never()).next();
    }

    @Test
    @DisplayName("List<Integer> @QueryParam: all in-range elements pass — ctx.next() is called")
    void webValidationGatePassesValidCollection() {
        ParamDescriptor ids = collectionParam("ids", ParamLocation.QUERY, List.class, Integer.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "ids",
                        new JsonObject()
                                .put("type", "array")
                                .put(
                                        "items",
                                        new JsonObject().put("type", "integer").put("maximum", 100)))
                .build();
        Handler<RoutingContext> gate =
                strategy.gateFor(op(List.of(ids)), schemas).orElseThrow();

        RoutingContext ok = mockContext(multiQuery("ids", "1", "2"));
        gate.handle(ok);

        verify(ok, times(1)).next();
        verify(ok, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("Set<String> @QueryParam: an element violating the item pattern is rejected (all values validated)")
    void setQueryParamValidatesAllValuesUnderWebValidation() {
        ParamDescriptor codes = collectionParam("codes", ParamLocation.QUERY, Set.class, String.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "codes",
                        new JsonObject()
                                .put("type", "array")
                                .put(
                                        "items",
                                        new JsonObject().put("type", "string").put("pattern", "[A-Z]+")))
                .build();
        Handler<RoutingContext> gate =
                strategy.gateFor(op(List.of(codes)), schemas).orElseThrow();

        // ?codes=AB&codes=cd — the second value violates the [A-Z]+ pattern.
        RoutingContext bad = mockContext(multiQuery("codes", "AB", "cd"));
        gate.handle(bad);

        RestValidationException ex = captureFailure(bad);
        assertTrue(references(ex, "codes") || references(ex, "pattern"), "violation must reference the bad element");
        verify(bad, never()).next();
    }
}
