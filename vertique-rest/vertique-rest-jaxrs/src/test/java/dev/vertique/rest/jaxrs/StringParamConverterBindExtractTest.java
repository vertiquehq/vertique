// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.COOKIE;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.HEADER;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.PATH;
import static dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionException;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverter;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bind-to-extract regression: an application {@link ParamConverterBinding} for {@link String} must
 * run for scalar PATH/QUERY/HEADER/COOKIE parameters after {@link DefaultBoundRequest} stores the raw
 * transport string. The binder no longer converts; the extractor must still consult
 * {@link ParamConversionResolver} for {@code String.class} (including a null-returning override).
 */
class StringParamConverterBindExtractTest {

    private static final String RAW = "raw";
    private static final String CONVERTED = "converted:raw";

    /** Counts {@code fromString} calls and prefixes the value when non-null results are wanted. */
    private static final class CountingStringConverter implements ParamConverter<String> {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean returnNull;

        CountingStringConverter(boolean returnNull) {
            this.returnNull = returnNull;
        }

        @Override
        public String fromString(String value) {
            calls.incrementAndGet();
            return returnNull ? null : "converted:" + value;
        }

        @Override
        public String toString(String value) {
            return value;
        }

        int calls() {
            return calls.get();
        }
    }

    /** Fixture resource with one scalar String method. */
    static final class ScalarResource {
        @SuppressWarnings("unused")
        public String scalar(String name) {
            return name;
        }
    }

    private static ParamConversionResolver resolverWith(CountingStringConverter converter) {
        return ParamConversionResolver.of(
                ParamConverterRegistry.of(Set.of(new ParamConverterBinding<>(String.class, converter))),
                Set.<ParamConverterProvider>of());
    }

    private static ResourceMethodMeta.ParamMeta paramMeta(String name, ResourceMethodMeta.ParamSource source) {
        return new ResourceMethodMeta.ParamMeta(name, source, String.class, null, null, null, (Annotation[]) null);
    }

    private static ResourceMethodMeta metaFor(ResourceMethodMeta.ParamSource source) throws Exception {
        Method method = ScalarResource.class.getMethod("scalar", String.class);
        return new ResourceMethodMeta(
                new ScalarResource(),
                method,
                method.getName(),
                "GET",
                "/things",
                List.of(paramMeta("name", source)),
                String.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static BoundRequest bind(
            ResourceMethodMeta meta,
            Map<String, String> pathParams,
            MultiMap query,
            MultiMap headers,
            Set<Cookie> cookies) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(pathParams != null ? pathParams : Map.of());
        when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(headers != null ? headers : MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(cookies != null ? cookies : Set.of());
        when(ctx.body()).thenReturn(null);
        return new DefaultBoundRequest(ctx, ResourceMethodMetaToDescriptorAdapter.adapt(meta));
    }

    private static Cookie cookie(String name, String value) {
        Cookie cookie = mock(Cookie.class);
        when(cookie.getName()).thenReturn(name);
        when(cookie.getValue()).thenReturn(value);
        return cookie;
    }

    private static MultiMap multiMap(String name, String value) {
        MultiMap map = MultiMap.caseInsensitiveMultiMap();
        map.add(name, value);
        return map;
    }

    private static ParameterExtractor extractor(ResourceMethodMeta meta, ParamConversionResolver resolver) {
        return new ParameterExtractor(
                meta, List.of(), new RestContextResolution(Set.of()), null, resolver, InputFieldNameResolver.IDENTITY);
    }

    private static Stream<Arguments> scalarSources() {
        return Stream.of(
                Arguments.of(PATH, (BindFn) meta -> bind(meta, Map.of("name", RAW), null, null, null)),
                Arguments.of(QUERY, (BindFn) meta -> bind(meta, null, multiMap("name", RAW), null, null)),
                Arguments.of(HEADER, (BindFn) meta -> bind(meta, null, null, multiMap("name", RAW), null)),
                Arguments.of(COOKIE, (BindFn) meta -> bind(meta, null, null, null, Set.of(cookie("name", RAW)))));
    }

    @FunctionalInterface
    private interface BindFn {
        BoundRequest bind(ResourceMethodMeta meta);
    }

    @ParameterizedTest(name = "{0}: String override runs after raw bind")
    @MethodSource("scalarSources")
    @DisplayName("scalar String ParamConverter override is applied bind-to-extract")
    void scalarStringOverride_appliedBindToExtract(ResourceMethodMeta.ParamSource source, BindFn bindFn)
            throws Exception {
        ResourceMethodMeta meta = metaFor(source);
        CountingStringConverter converter = new CountingStringConverter(false);
        BoundRequest req = bindFn.bind(meta);

        Object[] args = extractor(meta, resolverWith(converter)).extractArguments(null, req);

        assertEquals(CONVERTED, args[0], source + " scalar String must consult the application converter");
        assertEquals(1, converter.calls(), source + " converter must run exactly once");
    }

    @Test
    @DisplayName("null-returning String override fails closed for a present scalar query value")
    void nullReturningStringOverride_failsClosed() throws Exception {
        ResourceMethodMeta meta = metaFor(QUERY);
        CountingStringConverter converter = new CountingStringConverter(true);
        BoundRequest req = bind(meta, null, multiMap("name", "secret-value"), null, null);

        ParamConversionException ex =
                assertThrows(ParamConversionException.class, () -> extractor(meta, resolverWith(converter))
                        .extractArguments(null, req));
        assertEquals(1, converter.calls(), "the null-returning override must still be consulted");
        assertEquals("name", ex.paramName());
        assertFalse(ex.getMessage().contains("secret-value"), "the raw value must not appear in the message");
    }
}
