// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Proves that {@link DefaultBoundRequest} binds scalar parameters through a
 * {@link ParamConversionResolver} rather than the legacy {@code ScalarCoercion} path
 * (PRD-REST-018 slice 1.3, the 3-site propagation contract).
 *
 * <p><b>RED rationale (compile-RED):</b> this test is written against the <em>intended</em>
 * resolver-backed {@code DefaultBoundRequest(RoutingContext, JaxRsOperationDescriptor,
 * ParamConversionResolver)} constructor, which does not exist yet — today {@code DefaultBoundRequest}
 * has only the two-arg constructor and coerces via {@code ScalarCoercion} directly. It therefore
 * compile-fails now. Once the resolver is threaded into the binding facade (and into all three
 * {@code DefaultBoundRequest} construction sites), this becomes a behavior assertion: an
 * app-contributed {@link ParamConverterBinding} override is honored on the binding path, which is
 * something the {@code ScalarCoercion} path can never do (it has no notion of app converters).
 *
 * <p>The override is a {@code UUID} converter that ignores its input and always yields a fixed
 * sentinel UUID. If binding routes through the resolver, the bound value equals the sentinel; if it
 * routes through {@code ScalarCoercion} (which has no UUID support and returns the raw string), it
 * does not. This single behavioral signal proves the resolver — not the legacy path — drove the bind.
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
    @DisplayName("DefaultBoundRequest binds a scalar through the supplied resolver, honoring an app converter override")
    void boundRequestBindsThroughResolverHonoringOverride() {
        ParamDescriptor idParam =
                new ParamDescriptor("id", ParamLocation.PATH, UUID.class, null, null, null, List.of());
        RoutingContext ctx = mockContext(Map.of("id", "11111111-1111-1111-1111-111111111111"));

        // INTENDED-but-not-yet-existing 3-arg constructor threading the resolver into the binding facade.
        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(idParam), resolverWithUuidOverride());

        // The override converter ignores the input and yields the sentinel; ScalarCoercion (the legacy
        // path) has no UUID support and would retain the raw string, so this proves the resolver drove
        // the bind, not the legacy path.
        assertEquals(
                SENTINEL,
                bound.pathParameters().get("id").get(),
                "the bound value must come from the app converter override via the resolver path");
    }
}
