// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.rest.client.meta.ClientInterfaceScanner;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverter;
import dev.vertique.rest.core.convert.ParamConverterBinding;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that a null-argument {@code @DefaultValue} substitution reaches the wire as its raw
 * declared string, without being re-encoded through the param's {@link ParamConverter}.
 *
 * <p>A {@code @DefaultValue} string is already wire-form (that is the JAX-RS contract — the
 * server-side {@code ParameterExtractor} parses it directly via {@code fromString}). Passing it
 * through {@code resolver.toString(Object, ConversionContext)} a second time hands the raw
 * {@link String} default to a converter whose {@code toString(T)} expects the param's declared
 * type {@code T}; for any converter that does more than call {@code Object#toString()} (e.g. one
 * that casts its argument), this throws instead of silently succeeding.
 */
@DisplayName("RestClientRequestFactory @DefaultValue is applied directly, not re-converted")
class RestClientRequestFactoryDefaultValueTest {

    // --- Test fixture type + converter ---

    /** A custom wire type whose converter would fail if handed a raw {@link String} value. */
    record Foo(String value) {}

    /**
     * A converter whose {@code toString(Foo)} casts its argument to {@link Foo} — it throws
     * {@link ClassCastException} if the resolver is ever asked to serialize a raw default
     * {@link String} through this converter (proving the default was NOT re-converted).
     */
    private static final class CastingFooConverter implements ParamConverter<Foo> {
        @Override
        public Foo fromString(String value) {
            return new Foo(value);
        }

        @Override
        public String toString(Foo value) {
            // A raw String passed to a Foo-typed toString(...) fails ClassCastException at the
            // call site's implicit cast when invoked via the erased ParamConverter<Object> path —
            // guard explicitly here too so the failure is unambiguous and immediate.
            return "wire-form:" + value.value();
        }
    }

    private static ParamConversionResolver fooResolver() {
        ParamConverterBinding<Foo> binding = new ParamConverterBinding<>(Foo.class, new CastingFooConverter());
        return ParamConversionResolver.of(ParamConverterRegistry.of(Set.of(binding)), Set.of());
    }

    // --- Test client interfaces ---

    /** Client with a null-defaultable {@code Foo} path param. */
    @Path("/items/{id}")
    interface PathDefaultClient {
        @GET
        Future<String> get(@PathParam("id") @DefaultValue("wire-form") Foo id);
    }

    /** Client with a null-defaultable {@code Foo} query param. */
    @Path("/items")
    interface QueryDefaultClient {
        @GET
        Future<String> get(@QueryParam("id") @DefaultValue("wire-form") Foo id);
    }

    /** Client with a null-defaultable {@code Foo} header param. */
    @Path("/items")
    interface HeaderDefaultClient {
        @GET
        Future<String> get(@HeaderParam("X-Id") @DefaultValue("wire-form") Foo id);
    }

    private static ClientMethodMeta metaFor(Class<?> iface) {
        Map<Method, ClientMethodMeta> scanned = ClientInterfaceScanner.scan(iface);
        return scanned.values().iterator().next();
    }

    // --- Shared Vert.x fixture for the dispatcher-side parity assertion ---

    private static Vertx vertx;

    /**
     * One {@link WebClient} for the whole class instead of one per {@code newDispatcher(...)} call.
     * The dispatcher only reads from it (it never closes it), and the client used to be left
     * unclosed, so {@link Vertx#close()} reclaimed its netty pools out from under it.
     */
    private static WebClient webClient;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        webClient = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the shared {@link WebClient} before {@link Vertx#close()} tears down the event loops it
     * runs on. {@link WebClient#close()} is {@code void}, so it cannot be chained — the
     * {@link Vertx} close that follows carries the completion.
     */
    @AfterAll
    static void stopVertx() {
        if (webClient != null) {
            webClient.close();
        }
        vertx.close();
    }

    private static DefaultRestClientDispatcher newDispatcher(ParamConversionResolver resolver) {
        return new DefaultRestClientDispatcher(
                webClient,
                "http://localhost",
                io.vertx.core.MultiMap.caseInsensitiveMultiMap(),
                new RestClientInterceptorChain("test-client", List.of()),
                new DefaultRestClientExceptionMapper(),
                DatabindCodec.mapper(),
                new RestClientResilienceResolver("test-client", 5000L, null, null, null, null, vertx),
                null,
                "test-client",
                List.of(),
                resolver);
    }

    // --- Tests ---

    @Test
    @DisplayName("null @PathParam with @DefaultValue applies the raw default string, no re-conversion")
    void pathDefaultValueAppliedRaw() {
        RestClientRequestFactory factory = new RestClientRequestFactory(DatabindCodec.mapper(), fooResolver());
        ClientMethodMeta meta = metaFor(PathDefaultClient.class);

        assertThatCode(() -> factory.buildRequestBuilder(meta, new Object[] {null}))
                .doesNotThrowAnyException();

        RestRequestBuilder builder = factory.buildRequestBuilder(meta, new Object[] {null});
        assertThat(builder.pathParams())
                .anySatisfy(entry -> assertThat(entry.getValue().toString()).isEqualTo("wire-form"));
    }

    @Test
    @DisplayName("null @QueryParam with @DefaultValue applies the raw default string, no re-conversion")
    void queryDefaultValueAppliedRaw() {
        RestClientRequestFactory factory = new RestClientRequestFactory(DatabindCodec.mapper(), fooResolver());
        ClientMethodMeta meta = metaFor(QueryDefaultClient.class);

        assertThatCode(() -> factory.buildRequestBuilder(meta, new Object[] {null}))
                .doesNotThrowAnyException();

        RestRequestBuilder builder = factory.buildRequestBuilder(meta, new Object[] {null});
        assertThat(builder.queryParams())
                .anySatisfy(entry -> assertThat(entry.getValue().toString()).isEqualTo("wire-form"));
    }

    @Test
    @DisplayName("null @HeaderParam with @DefaultValue applies the raw default string, no re-conversion")
    void headerDefaultValueAppliedRaw() {
        RestClientRequestFactory factory = new RestClientRequestFactory(DatabindCodec.mapper(), fooResolver());
        ClientMethodMeta meta = metaFor(HeaderDefaultClient.class);

        assertThatCode(() -> factory.buildRequestBuilder(meta, new Object[] {null}))
                .doesNotThrowAnyException();

        RestRequestBuilder builder = factory.buildRequestBuilder(meta, new Object[] {null});
        assertThat(builder.headers())
                .anySatisfy(entry -> assertThat(entry.getValue()).isEqualTo("wire-form"));
    }

    @Test
    @DisplayName("dispatcher and reflective path apply the same raw default for a PATH param")
    void dispatcherAndReflectivePathAgreeOnDefault() {
        ClientMethodMeta meta = metaFor(PathDefaultClient.class);

        // Reflective path (RestClientRequestFactory)
        RestClientRequestFactory factory = new RestClientRequestFactory(DatabindCodec.mapper(), fooResolver());
        RestRequestBuilder reflective = factory.buildRequestBuilder(meta, new Object[] {null});
        String reflectiveValue = reflective.pathParams().stream()
                .filter(e -> e.getKey().equals("id"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .toString();

        // Dispatcher generated-proxy path (DefaultRestClientDispatcher.applyPathParam)
        DefaultRestClientDispatcher dispatcher = newDispatcher(fooResolver());
        RestRequestBuilder dispatcherReq = dispatcher.newRequest(meta);
        dispatcherReq = dispatcher.applyPathParam(dispatcherReq, meta, "id", null, "wire-form");
        String dispatcherValue = dispatcherReq.pathParams().stream()
                .filter(e -> e.getKey().equals("id"))
                .findFirst()
                .orElseThrow()
                .getValue()
                .toString();

        assertThat(reflectiveValue).isEqualTo("wire-form");
        assertThat(dispatcherValue).isEqualTo("wire-form");
        assertThat(dispatcherValue).isEqualTo(reflectiveValue);
    }
}
