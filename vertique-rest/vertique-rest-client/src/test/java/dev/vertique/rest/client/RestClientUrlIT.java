// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for the {@link Url} parameter annotation feature, using WireMock as the HTTP
 * server.
 *
 * <p>Verifies that per-call absolute URI overrides work end-to-end: correct HTTP dispatch,
 * query-param merging, header/cookie/body coexistence, runtime validation errors, builder
 * validation, and interceptor context propagation.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestClientUrlIT {

    // --- WireMock lifecycle ---

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    /**
     * Resets all WireMock stubs and serve-events before each test so that stub registrations and
     * request counts from one test do not bleed into the next.
     */
    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // --- Test DTOs ---

    /** Item DTO used across test scenarios. */
    record Item(String name) {}

    // --- Test client interfaces ---

    /**
     * All-{@link Url} interface — no {@code baseUrl} is required because every method supplies its
     * own absolute URI at call time.
     */
    @RestClient(name = "url-client")
    @Produces("application/json")
    interface UrlClient {
        /**
         * Simple GET that overrides the target URI entirely.
         *
         * @param url absolute URI to call
         * @return deserialized item
         */
        @GET
        Future<Item> getItem(@Url URI url);

        /**
         * GET that merges {@code @QueryParam} values into the supplied URI's query string.
         *
         * @param url absolute base URI (may already contain query params)
         * @param added value for the {@code added} query parameter
         * @return deserialized item
         */
        @GET
        Future<Item> getWithQuery(@Url URI url, @QueryParam("added") String added);

        /**
         * GET that sends an additional header alongside the dynamic URI.
         *
         * @param url absolute URI to call
         * @param token value for the {@code X-Token} header
         * @return deserialized item
         */
        @GET
        Future<Item> getWithHeader(@Url URI url, @HeaderParam("X-Token") String token);

        /**
         * GET that sends a cookie alongside the dynamic URI.
         *
         * @param url absolute URI to call
         * @param session value for the {@code session} cookie
         * @return deserialized item
         */
        @GET
        Future<Item> getWithCookie(@Url URI url, @CookieParam("session") String session);

        /**
         * POST that sends a JSON body alongside the dynamic URI.
         *
         * @param url absolute URI to call
         * @param body request body
         * @return deserialized item
         */
        @POST
        Future<Item> postWithBody(@Url URI url, Item body);
    }

    /**
     * All-{@link Url} interface used to verify that {@link RestClientBuilder#build} succeeds even
     * when no {@code baseUrl} has been configured.
     */
    @RestClient(name = "all-url-client")
    interface AllUrlClient {
        /**
         * First method.
         *
         * @param url absolute URI
         * @return deserialized item
         */
        @GET
        Future<Item> method1(@Url URI url);

        /**
         * Second method.
         *
         * @param url absolute URI
         * @param body request body
         * @return deserialized item
         */
        @POST
        Future<Item> method2(@Url URI url, Item body);
    }

    /**
     * Ordinary path-based interface used to verify that {@link RestClientBuilder#build} throws
     * {@link IllegalArgumentException} when no {@code baseUrl} has been configured.
     */
    @RestClient(name = "simple-client")
    @Path("/api")
    @Produces("application/json")
    interface SimpleClient {
        /**
         * Gets an item.
         *
         * @return deserialized item
         */
        @GET
        @Path("/items")
        Future<Item> getItems();
    }

    // --- Builder helpers ---

    /**
     * Creates a {@link UrlClient} backed by a builder without a {@code baseUrl}.
     *
     * @param vertx the Vert.x instance
     * @return a ready-to-use {@link UrlClient}
     */
    private UrlClient buildClient(Vertx vertx) {
        return new RestClientBuilder(vertx).build(UrlClient.class);
    }

    /**
     * Returns the WireMock base URL for use as a dynamic URI prefix in tests, pinned to the IPv4
     * loopback address the server is bound to.
     *
     * @return base URL string, e.g. {@code http://127.0.0.1:54321}
     */
    private String baseUrl() {
        return "http://127.0.0.1:" + wireMock.getPort();
    }

    // --- Happy-path tests ---

    @Test
    @DisplayName("@Url GET 200 response is deserialized correctly")
    void dynamicUrlGet200(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/dynamic/items/1")).willReturn(okJson("{\"name\":\"dynamic-item\"}")));

        URI url = URI.create(baseUrl() + "/dynamic/items/1");
        buildClient(vertx)
                .getItem(url)
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("dynamic-item");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@Url 4xx response produces RestClientResponseException with dynamic URI")
    void dynamicUrl4xxProducesResponseException(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/dynamic/error"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        URI url = URI.create(baseUrl() + "/dynamic/error");
        buildClient(vertx)
                .getItem(url)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(404);
                    assertThat(ex.requestContext().requestUri()).contains("/dynamic/error");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@QueryParam values are merged into the URI's existing query string")
    void queryParamMerge(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/dynamic/search?existing=1&added=2")).willReturn(okJson("{\"name\":\"merged\"}")));

        URI url = URI.create(baseUrl() + "/dynamic/search?existing=1");
        buildClient(vertx)
                .getWithQuery(url, "2")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("merged");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@HeaderParam is sent correctly alongside @Url")
    void headerAlongsideUrl(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/dynamic/secure"))
                .withHeader("X-Token", equalTo("abc"))
                .willReturn(okJson("{\"name\":\"secure\"}")));

        URI url = URI.create(baseUrl() + "/dynamic/secure");
        buildClient(vertx)
                .getWithHeader(url, "abc")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("secure");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@CookieParam is sent correctly alongside @Url")
    void cookieAlongsideUrl(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/dynamic/cookie"))
                .withHeader("Cookie", equalTo("session=xyz"))
                .willReturn(okJson("{\"name\":\"cookie\"}")));

        URI url = URI.create(baseUrl() + "/dynamic/cookie");
        buildClient(vertx)
                .getWithCookie(url, "xyz")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("cookie");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("JSON body is sent correctly in POST alongside @Url")
    void bodyAlongsideUrl(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(post(urlEqualTo("/dynamic/create"))
                .withRequestBody(equalToJson("{\"name\":\"test\"}"))
                .willReturn(okJson("{\"name\":\"test\"}")));

        URI url = URI.create(baseUrl() + "/dynamic/create");
        buildClient(vertx)
                .postWithBody(url, new Item("test"))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("test");
                    ctx.completeNow();
                })));
    }

    // --- Runtime validation error tests ---

    @Test
    @DisplayName("Null URI argument fails with RestClientException")
    void nullUriThrowsRestClientException(Vertx vertx, VertxTestContext ctx) {
        buildClient(vertx)
                .getItem(null)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Relative URI argument fails with RestClientException")
    void relativeUriThrowsRestClientException(Vertx vertx, VertxTestContext ctx) {
        buildClient(vertx)
                .getItem(URI.create("/relative"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Non-HTTP scheme URI fails with RestClientException")
    void nonHttpSchemeThrowsRestClientException(Vertx vertx, VertxTestContext ctx) {
        buildClient(vertx)
                .getItem(URI.create("ftp://example.com/file"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("URI with fragment fails with RestClientException")
    void fragmentInUriThrowsRestClientException(Vertx vertx, VertxTestContext ctx) {
        buildClient(vertx)
                .getItem(URI.create("https://example.com/path#frag"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientException.class);
                    ctx.completeNow();
                })));
    }

    // --- Builder validation tests ---

    @Test
    @DisplayName("All-@Url interface builds successfully without a baseUrl")
    void allUrlInterfaceBuildsWithoutBaseUrl(Vertx vertx, VertxTestContext ctx) {
        // Should not throw
        AllUrlClient client = new RestClientBuilder(vertx).build(AllUrlClient.class);
        assertThat(client).isNotNull();
        ctx.completeNow();
    }

    @Test
    @DisplayName("Path-based interface without baseUrl throws IllegalArgumentException at build time")
    void mixedInterfaceRequiresBaseUrl(Vertx vertx, VertxTestContext ctx) {
        assertThatThrownBy(() -> new RestClientBuilder(vertx).build(SimpleClient.class))
                .isInstanceOf(IllegalArgumentException.class);
        ctx.completeNow();
    }

    // --- Interceptor context propagation tests ---

    @Test
    @DisplayName("beforeRequest interceptor sees the @Url-derived absolute URI")
    void interceptorSeesUrlDerivedUri(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/dynamic/items/99")).willReturn(okJson("{\"name\":\"observed\"}")));

        URI dynamicUrl = URI.create(baseUrl() + "/dynamic/items/99");
        AtomicReference<String> capturedUri = new AtomicReference<>();

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                capturedUri.set(reqCtx.requestUri());
                return Future.succeededFuture(reqCtx);
            }
        };

        new RestClientBuilder(vertx)
                .register(interceptor)
                .build(UrlClient.class)
                .getItem(dynamicUrl)
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(capturedUri.get()).contains("/dynamic/items/99");
                    ctx.completeNow();
                })));
    }

    // --- mergeQueryParams edge-case tests ---

    @Test
    @DisplayName("URI with trailing bare '?' does not produce '?&' when query params are absent")
    void trailingBareQuestionMarkNotDoubled(Vertx vertx, VertxTestContext ctx) {
        // The URI has a trailing '?' but no query string value; mergeQueryParams must not
        // produce the malformed '?&q=1' that the old string-level implementation would emit.
        wireMock.stubFor(get(urlEqualTo("/dynamic/search")).willReturn(okJson("{\"name\":\"bare-q\"}")));

        // URI.create strips the bare '?' during parsing — getRawQuery() returns null;
        // the dispatcher should reconstruct as '/dynamic/search' with no leading '?'.
        URI url = URI.create(baseUrl() + "/dynamic/search?");
        buildClient(vertx)
                .getItem(url)
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("bare-q");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Authority-only URI with @QueryParam inserts '/' before '?' in assembled URI")
    void authorityOnlyUriInsertsSlashBeforeQuery(Vertx vertx, VertxTestContext ctx) {
        // An authority-only URI like 'http://host:port' (no path) must become
        // 'http://host:port/?added=2' — not 'http://host:port?added=2' — to satisfy
        // strict HTTP servers that require a path component before the query string.
        wireMock.stubFor(get(urlEqualTo("/?added=2")).willReturn(okJson("{\"name\":\"root\"}")));

        URI url = URI.create(baseUrl());
        buildClient(vertx)
                .getWithQuery(url, "2")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("root");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor URI rewrite is visible in RestClientResponseException.requestContext()")
    void interceptorRewritePropagatedToFailure(Vertx vertx, VertxTestContext ctx) {
        String rewrittenPath = "/dynamic/rewritten";
        wireMock.stubFor(get(urlEqualTo(rewrittenPath))
                .willReturn(aResponse().withStatus(500).withBody("server error")));

        URI originalUrl = URI.create(baseUrl() + "/dynamic/original");
        URI rewrittenUrl = URI.create(baseUrl() + rewrittenPath);

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                return Future.succeededFuture(reqCtx.withRequestUri(rewrittenUrl.toString()));
            }
        };

        new RestClientBuilder(vertx)
                .register(interceptor)
                .build(UrlClient.class)
                .getItem(originalUrl)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.requestContext().requestUri()).contains(rewrittenPath);
                    ctx.completeNow();
                })));
    }
}
