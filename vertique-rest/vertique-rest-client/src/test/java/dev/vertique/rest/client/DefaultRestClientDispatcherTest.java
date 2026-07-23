// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.meta.ClientInterfaceScanner;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.convert.ParamConverterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link DefaultRestClientDispatcher}'s generated-proxy param-apply
 * methods ({@code applyPathParam}/{@code applyQueryParam}/{@code applyHeaderParam}/
 * {@code applyCookieParam}), proving parity with the JDK reflective proxy path
 * ({@link RestClientRequestFactory}) for duplicate wire names across sources and
 * array-valued query parameters.
 */
@DisplayName("DefaultRestClientDispatcher param-apply parity")
class DefaultRestClientDispatcherTest {

    // --- Test client interfaces ---

    /**
     * Client with a wire name "x" shared between a PATH param (enum-typed, so its converter calls
     * {@code Enum#name()} and throws {@link ClassCastException} on a non-enum value) and a QUERY
     * param (plain {@code String}). Using the wrong meta for either apply-call is observable: the
     * PATH meta's enum converter cannot serialize a {@code String} QUERY value.
     */
    @Path("/items/{x}")
    interface DuplicateWireNameClient {
        @GET
        Future<String> get(@PathParam("x") Mode pathX, @QueryParam("x") String queryX);
    }

    /** Enum used as the PATH param type in {@link DuplicateWireNameClient}. */
    enum Mode {
        FAST,
        SLOW
    }

    /** Client with an array-valued (UUID[]) query parameter. */
    @Path("/items")
    interface ArrayQueryParamClient {
        @GET
        Future<String> byIds(@QueryParam("ids") UUID[] ids);
    }

    /** Client with a {@code text/plain} body parameter. */
    @Path("/text")
    interface TextPlainBodyClient {
        @POST
        @Consumes("text/plain")
        Future<String> post(String body);
    }

    /** Client with an {@code application/octet-stream} body parameter. */
    @Path("/bytes")
    interface OctetStreamBodyClient {
        @POST
        @Consumes("application/octet-stream")
        Future<String> post(byte[] body);
    }

    /** Client with a default (JSON) body parameter. */
    @Path("/json")
    interface JsonBodyClient {
        @POST
        Future<String> post(Widget body);
    }

    /** Simple POJO used as a JSON body value. */
    static final class Widget {
        public String name = "widget";
    }

    /** Client with a {@code @Url} parameter, typed {@link URI} per framework convention. */
    interface UrlParamClient {
        @GET
        Future<String> get(@dev.vertique.rest.client.Url URI url);
    }

    // --- Shared Vert.x / dispatcher fixtures ---

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    private static ClientMethodMeta metaFor(Class<?> iface, String methodName) {
        Map<Method, ClientMethodMeta> scanned = ClientInterfaceScanner.scan(iface);
        return scanned.values().stream()
                .filter(m -> m.methodMetadata().name().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private static DefaultRestClientDispatcher newDispatcher(ParamConversionResolver resolver) {
        WebClient webClient = WebClient.create(vertx);
        return new DefaultRestClientDispatcher(
                webClient,
                "http://localhost",
                io.vertx.core.MultiMap.caseInsensitiveMultiMap(),
                new RestClientInterceptorChain("test-client", List.of()),
                new DefaultRestClientExceptionMapper(),
                io.vertx.core.json.jackson.DatabindCodec.mapper(),
                new RestClientResilienceResolver("test-client", 5000L, null, null, null, null, vertx),
                null,
                "test-client",
                List.of(),
                resolver);
    }

    // --- FIX C: duplicate wire-name resolves the wrong ClientParamMeta ---

    @Test
    @DisplayName("applyQueryParam resolves the QUERY meta, not the PATH meta, for a duplicate wire name")
    void applyQueryParamResolvesQueryMetaNotPathMetaForDuplicateWireName() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(DuplicateWireNameClient.class, "get");

        RestRequestBuilder req = dispatcher.newRequest(meta);
        // Populate the PATH "x" (enum-typed) first so a name-only lookup finds it ahead of QUERY "x".
        req = dispatcher.applyPathParam(req, meta, "x", Mode.FAST, null);

        // If applyQueryParam resolves the PATH meta (enum-typed) instead of the QUERY meta
        // (String-typed) for the same wire name "x", the enum converter's toString(Object) casts
        // the String query value to Enum and throws ClassCastException.
        RestRequestBuilder finalReq = req;
        RestRequestBuilder[] populatedHolder = new RestRequestBuilder[1];
        assertThatCode(() -> populatedHolder[0] = dispatcher.applyQueryParam(finalReq, meta, "x", "query-value", null))
                .doesNotThrowAnyException();

        RestRequestBuilder populated = populatedHolder[0];
        assertThat(populated.queryParams()).anySatisfy(entry -> {
            assertThat(entry.getKey()).isEqualTo("x");
            assertThat(entry.getValue().toString()).isEqualTo("query-value");
        });
        // The path substitution independently carries the PATH value (proves no cross-contamination).
        assertThat(populated.pathParams()).anySatisfy(entry -> {
            assertThat(entry.getKey()).isEqualTo("x");
            assertThat(entry.getValue().toString()).isEqualTo("FAST");
        });
    }

    // --- FIX D: array-valued params validate OK but break at runtime ---

    @Test
    @DisplayName("applyQueryParam expands a UUID[] array value element-by-element")
    void applyQueryParamExpandsArrayValueElementByElement() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(ArrayQueryParamClient.class, "byIds");

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID[] ids = new UUID[] {a, b};

        RestRequestBuilder req = dispatcher.newRequest(meta);
        RestRequestBuilder[] populatedHolder = new RestRequestBuilder[1];
        assertThatCode(() -> populatedHolder[0] = dispatcher.applyQueryParam(req, meta, "ids", ids, null))
                .doesNotThrowAnyException();

        RestRequestBuilder populated = populatedHolder[0];
        assertThat(populated.queryParams())
                .extracting(entry -> entry.getValue().toString())
                .contains(a.toString(), b.toString());
        assertThat(populated.queryParams()).hasSize(2);
    }

    // --- Generated-proxy body parity (applyBody) ---

    @Test
    @DisplayName("applyBody with text/plain media type produces raw UTF-8 bytes, not JSON-quoted")
    void applyBodyWithTextPlainProducesRawBytes() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(TextPlainBodyClient.class, "post");

        RestRequestBuilder req = dispatcher.newRequest(meta);
        RestRequestBuilder populated = dispatcher.applyBody(req, meta, "hello world");

        Buffer body = populated.body();
        assertThat(body).isNotNull();
        assertThat(body.toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    @DisplayName("applyBody with application/octet-stream media type passes byte[] through unchanged")
    void applyBodyWithOctetStreamPassesBytesThrough() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(OctetStreamBodyClient.class, "post");

        byte[] raw = new byte[] {1, 2, 3, 4, 5};
        RestRequestBuilder req = dispatcher.newRequest(meta);
        RestRequestBuilder populated = dispatcher.applyBody(req, meta, raw);

        Buffer body = populated.body();
        assertThat(body).isNotNull();
        assertThat(body.getBytes()).isEqualTo(raw);
    }

    @Test
    @DisplayName("applyBody with default media type JSON-serializes the value (regression coverage)")
    void applyBodyWithDefaultMediaTypeJsonSerializes() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(JsonBodyClient.class, "post");

        Widget widget = new Widget();
        RestRequestBuilder req = dispatcher.newRequest(meta);
        RestRequestBuilder populated = dispatcher.applyBody(req, meta, widget);

        Buffer body = populated.body();
        assertThat(body).isNotNull();
        assertThat(body.toString(StandardCharsets.UTF_8)).contains("\"name\"", "\"widget\"");
    }

    // --- Generated-proxy @Url parity (applyUrlParam) ---

    @Test
    @DisplayName("applyUrlParam with a relative URI throws RestClientException mentioning absolute")
    void applyUrlParamWithRelativeUriThrows() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(UrlParamClient.class, "get");
        RestRequestBuilder req = dispatcher.newRequest(meta);

        URI relative = URI.create("/just/a/path");

        assertThatThrownBy(() -> dispatcher.applyUrlParam(req, meta, relative))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("applyUrlParam with a non-http(s) scheme throws RestClientException mentioning scheme")
    void applyUrlParamWithNonHttpSchemeThrows() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(UrlParamClient.class, "get");
        RestRequestBuilder req = dispatcher.newRequest(meta);

        URI ftpUri = URI.create("ftp://host/path");

        assertThatThrownBy(() -> dispatcher.applyUrlParam(req, meta, ftpUri))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("applyUrlParam with a fragment throws RestClientException mentioning fragment")
    void applyUrlParamWithFragmentThrows() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(UrlParamClient.class, "get");
        RestRequestBuilder req = dispatcher.newRequest(meta);

        URI withFragment = URI.create("https://host/path#frag");

        assertThatThrownBy(() -> dispatcher.applyUrlParam(req, meta, withFragment))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("fragment");
    }

    @Test
    @DisplayName("applyUrlParam with a valid absolute http(s) URI succeeds and populates absoluteUri")
    void applyUrlParamWithValidUriSucceeds() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(UrlParamClient.class, "get");
        RestRequestBuilder req = dispatcher.newRequest(meta);

        URI valid = URI.create("https://example.com/path");

        RestRequestBuilder populated = dispatcher.applyUrlParam(req, meta, valid);

        assertThat(populated.absoluteUri()).isEqualTo("https://example.com/path");
    }

    @Test
    @DisplayName("applyUrlParam with null does not throw and leaves absoluteUri null (defer-to-assembleUri contract)")
    void applyUrlParamWithNullDoesNotThrowAndLeavesAbsoluteUriNull() {
        ParamConversionResolver resolver = ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), Set.of());
        DefaultRestClientDispatcher dispatcher = newDispatcher(resolver);
        ClientMethodMeta meta = metaFor(UrlParamClient.class, "get");
        RestRequestBuilder req = dispatcher.newRequest(meta);

        RestRequestBuilder[] populatedHolder = new RestRequestBuilder[1];
        assertThatCode(() -> populatedHolder[0] = dispatcher.applyUrlParam(req, meta, null))
                .doesNotThrowAnyException();

        assertThat(populatedHolder[0].absoluteUri()).isNull();
    }
}
