// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverter;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link DefaultBoundRequest} stores declared scalars as raw strings. The resolver
 * argument stays on the constructor so existing callers keep compiling, and a null resolver is
 * rejected. Application converters run later, in {@code ParameterExtractor}, after input policies.
 *
 * <p>The override is a {@code UUID} converter that ignores its input and always yields a fixed
 * sentinel UUID. If binding applied that converter, the bound value would be the sentinel. It must
 * stay the transport string.
 */
class BoundRequestResolverPropagationTest {

    private static final UUID SENTINEL = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** App converter that ignores its input and always produces the sentinel UUID. */
    private static final class SentinelUuidConverter implements ParamConverter<UUID> {
        @Override
        public UUID fromString(String value) {
            return SENTINEL;
        }

        @Override
        public String toString(UUID value) {
            return value.toString();
        }
    }

    /**
     * Builds a resolver whose registry carries an app {@link ParamConverterBinding} override for
     * {@link UUID} (the sentinel converter) and no JAX-RS providers.
     *
     * @return the resolver under test
     */
    private static ParamConversionResolver resolverWithUuidOverride() {
        ParamConverterRegistry registry =
                ParamConverterRegistry.of(Set.of(new ParamConverterBinding<>(UUID.class, new SentinelUuidConverter())));
        return ParamConversionResolver.of(registry, Set.<ParamConverterProvider>of());
    }

    /** Builds a single-path-param GET operation descriptor stub. */
    private static JaxRsOperationDescriptor opWithParams(ParamDescriptor... params) {
        return StubOperationDescriptor.builder()
                .operationId("op")
                .httpMethod("GET")
                .routeTemplate("/test")
                .parameters(List.of(params))
                .build();
    }

    /** Builds a mocked {@link RoutingContext} carrying a single path param. */
    private static RoutingContext mockContext(Map<String, String> pathParams) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(pathParams);
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(ctx.body()).thenReturn(null);
        return ctx;
    }

    @Test
    @DisplayName("DefaultBoundRequest keeps the raw string even when the supplied resolver would rewrite it")
    void boundRequestKeepsRawStringWhenResolverWouldRewriteIt() {
        ParamDescriptor idParam =
                new ParamDescriptor("id", ParamLocation.PATH, UUID.class, null, null, null, List.of());
        String raw = "11111111-1111-1111-1111-111111111111";
        RoutingContext ctx = mockContext(Map.of("id", raw));

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(idParam), resolverWithUuidOverride());

        assertEquals(raw, bound.pathParameters().get("id").get());
    }

    @Test
    @DisplayName("DefaultBoundRequest rejects a null resolver")
    void boundRequestRejectsNullResolver() {
        RoutingContext ctx = mockContext(Map.of());

        assertThrows(NullPointerException.class, () -> new DefaultBoundRequest(ctx, opWithParams(), null));
    }
}
