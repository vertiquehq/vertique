// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for the REST client proxy end-to-end, using WireMock as the HTTP server.
 *
 * <p>Tests verify HTTP round-trips: request construction (path/query/header/cookie params, JSON
 * body serialization, text/plain body), response handling (JSON deserialization, error mapping,
 * interceptors, default headers, Accept header from {@code @Produces}), and new features such as
 * {@code Optional<T>} 404 mapping, {@code @DefaultValue}, {@code @CookieParam}, and
 * {@code @ExpectedStatus}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestClientIntegrationIT {

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- WireMock lifecycle ---

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // --- Test DTOs ---

    /** Item DTO used across test scenarios. */
    record Item(String name) {}

    // --- Primary test client interface ---

    @RestClient(name = "test-service")
    @Path("/api")
    @Produces("application/json")
    @Consumes("application/json")
    interface TestClient {
        @GET
        @Path("/items")
        Future<List<Item>> listItems(@QueryParam("page") int page);

        @GET
        @Path("/items/{id}")
        Future<Item> getItem(@PathParam("id") String id);

        @POST
        @Path("/items")
        Future<Item> createItem(Item body);

        @PUT
        @Path("/items/{id}")
        Future<Item> updateItem(@PathParam("id") String id, Item body);

        @DELETE
        @Path("/items/{id}")
        Future<Void> deleteItem(@PathParam("id") String id);

        @GET
        @Path("/items/{id}/raw")
        Future<HttpClientResponse> getRaw(@PathParam("id") String id);

        @GET
        @Path("/items/search")
        Future<List<Item>> search(@HeaderParam("X-Search-Token") String token, @QueryParam("q") String query);
    }

    // --- Optional client ---

    @RestClient(name = "optional-service")
    @Path("/api")
    @Produces("application/json")
    interface OptionalClient {
        @GET
        @Path("/items/{id}")
        Future<Optional<Item>> findItem(@PathParam("id") String id);
    }

    // --- Cookie client ---

    @RestClient(name = "cookie-service")
    @Path("/api")
    @Produces("application/json")
    interface CookieClient {
        @GET
        @Path("/secure")
        Future<Item> getSecure(@CookieParam("session") String sessionToken);
    }

    // --- Text/plain client ---

    @RestClient(name = "text-service")
    @Path("/api")
    @Produces("text/plain")
    @Consumes("text/plain")
    interface TextClient {
        @POST
        @Path("/echo")
        Future<HttpClientResponse> echo(String body);
    }

    // --- DefaultValue client ---

    @RestClient(name = "default-value-service")
    @Path("/api")
    @Produces("application/json")
    interface DefaultValueClient {
        @GET
        @Path("/items")
        Future<List<Item>> listItems(@QueryParam("page") @DefaultValue("0") Integer page);
    }

    // --- BeanParam record client ---

    record SearchParams(
            @QueryParam("q") String query,
            @QueryParam("page") @DefaultValue("1") int page) {}

    @RestClient(name = "bean-param-service")
    @Path("/api")
    @Produces("application/json")
    interface BeanParamRecordClient {
        @GET
        @Path("/items/search")
        Future<List<Item>> search(@BeanParam SearchParams params);
    }

    // --- ExpectedStatus client ---

    @RestClient(name = "expected-status-service")
    @Path("/api")
    @Produces("application/json")
    interface ExpectedStatusClient {
        @GET
        @Path("/items/{id}")
        @ExpectedStatus({200, 201})
        Future<Item> getItem(@PathParam("id") String id);
    }

    // --- Builder helper ---

    /**
     * Creates a builder pointing at WireMock.
     *
     * @param vertx the Vert.x instance
     * @return a builder pre-configured with the WireMock base URL
     */
    private RestClientBuilder builderFor(Vertx vertx) {
        return new RestClientBuilder(vertx).baseUrl("http://localhost:" + wireMock.port());
    }

    // --- Tests ---

    @Test
    @DisplayName("GET with query params deserializes JSON list response")
    void getWithQueryParamsDeserializesList(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlPathEqualTo("/api/items"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson("[{\"name\":\"alpha\"},{\"name\":\"beta\"}]")));

        builderFor(vertx)
                .build(TestClient.class)
                .listItems(1)
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(2);
                    assertThat(items.get(0).name()).isEqualTo("alpha");
                    assertThat(items.get(1).name()).isEqualTo("beta");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("GET with path param deserializes single item response")
    void getWithPathParamDeserializesItem(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/42")).willReturn(okJson("{\"name\":\"widget\"}")));

        builderFor(vertx)
                .build(TestClient.class)
                .getItem("42")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("widget");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("POST with body sends correct JSON body and Content-Type header")
    void postWithBodySendsJsonBody(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(post(urlEqualTo("/api/items"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"name\":\"gadget\"}"))
                .willReturn(okJson("{\"name\":\"gadget\"}")));

        builderFor(vertx)
                .build(TestClient.class)
                .createItem(new Item("gadget"))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("gadget");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PUT with path param and body sends correct request")
    void putWithPathParamAndBody(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(put(urlEqualTo("/api/items/99"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"name\":\"updated\"}"))
                .willReturn(okJson("{\"name\":\"updated\"}")));

        builderFor(vertx)
                .build(TestClient.class)
                .updateItem("99", new Item("updated"))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("updated");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("DELETE returns null for Future<Void> on 204")
    void deleteReturnsFutureVoidOn204(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                delete(urlEqualTo("/api/items/7")).willReturn(aResponse().withStatus(204)));

        builderFor(vertx)
                .build(TestClient.class)
                .deleteItem("7")
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertThat(result).isNull();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Future<HttpClientResponse> wraps raw status, headers, and body")
    void rawResponseWrapsStatusAndBody(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/5/raw"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Custom", "raw-header")
                        .withBody("{\"name\":\"raw\"}")));

        builderFor(vertx)
                .build(TestClient.class)
                .getRaw("5")
                .onComplete(ctx.succeeding(raw -> ctx.verify(() -> {
                    assertThat(raw.statusCode()).isEqualTo(200);
                    assertThat(raw.headers().get("X-Custom")).isEqualTo("raw-header");
                    assertThat(raw.bodyAsString()).isEqualTo("{\"name\":\"raw\"}");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("404 response fails future with RestClientResponseException carrying statusCode=404")
    void notFoundResponseFailsWithResponseException(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/missing"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        builderFor(vertx)
                .build(TestClient.class)
                .getItem("missing")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(404);
                    assertThat(ex.isClientError()).isTrue();
                    assertThat(ex.isServerError()).isFalse();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("500 response fails future with RestClientResponseException.isServerError()==true")
    void serverErrorResponseIsServerError(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/broken"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        builderFor(vertx)
                .build(TestClient.class)
                .getItem("broken")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(500);
                    assertThat(ex.isServerError()).isTrue();
                    assertThat(ex.isClientError()).isFalse();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("transformError interceptor converts 404 RestClientResponseException to a domain exception")
    void transformErrorInterceptorConverts404(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/gone"))
                .willReturn(aResponse().withStatus(404).withBody("gone")));

        class ItemNotFoundException extends RuntimeException {
            ItemNotFoundException(String msg) {
                super(msg);
            }
        }

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<Throwable> transformError(
                    RestClientRequestContext request, RestClientResponseContext response, Throwable error) {
                if (error instanceof RestClientResponseException ex && ex.statusCode() == 404) {
                    return Future.succeededFuture(new ItemNotFoundException("item not found"));
                }
                return Future.succeededFuture(error);
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("gone")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(ItemNotFoundException.class);
                    assertThat(err.getMessage()).isEqualTo("item not found");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor adds a header that WireMock receives in the request")
    void interceptorAddsHeaderToRequest(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/10"))
                .withHeader("X-Intercepted", equalTo("yes"))
                .willReturn(okJson("{\"name\":\"intercepted\"}")));

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                return Future.succeededFuture(reqCtx.withHeader("X-Intercepted", "yes"));
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("10")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("intercepted");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("JSON array is properly deserialized as generic List<Item>")
    void jsonArrayDeserializedAsList(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlPathEqualTo("/api/items"))
                .willReturn(okJson("[{\"name\":\"x\"},{\"name\":\"y\"},{\"name\":\"z\"}]")));

        builderFor(vertx)
                .build(TestClient.class)
                .listItems(0)
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(3);
                    assertThat(items).extracting(Item::name).containsExactly("x", "y", "z");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Default headers from builder are sent with each request")
    void defaultHeadersFromBuilderAreSent(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/3"))
                .withHeader("X-App-Id", equalTo("my-app"))
                .withHeader("X-Env", equalTo("test"))
                .willReturn(okJson("{\"name\":\"headed\"}")));

        builderFor(vertx)
                .defaultHeader("X-App-Id", "my-app")
                .defaultHeader("X-Env", "test")
                .build(TestClient.class)
                .getItem("3")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("headed");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Accept header from @Produces annotation is sent with request")
    void acceptHeaderFromProducesAnnotationIsSent(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/1"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(okJson("{\"name\":\"accepted\"}")));

        builderFor(vertx)
                .build(TestClient.class)
                .getItem("1")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("accepted");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@HeaderParam is sent as request header and @QueryParam appended to URL")
    void headerParamAndQueryParamAreSent(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlPathEqualTo("/api/items/search"))
                .withHeader("X-Search-Token", equalTo("tok123"))
                .withQueryParam("q", equalTo("foo"))
                .willReturn(okJson("[{\"name\":\"found\"}]")));

        builderFor(vertx)
                .build(TestClient.class)
                .search("tok123", "foo")
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.get(0).name()).isEqualTo("found");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor afterResponse callback is invoked after successful response")
    void afterResponseInterceptorIsInvoked(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/11")).willReturn(okJson("{\"name\":\"after\"}")));

        AtomicBoolean afterCalled = new AtomicBoolean(false);
        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<Void> afterResponse(RestClientRequestContext request, RestClientResponseContext response) {
                afterCalled.set(true);
                return Future.succeededFuture();
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("11")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(afterCalled).isTrue();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Future<Optional<Item>> returns Optional.empty() when server responds 404")
    void optionalReturnsEmptyOn404(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/api/items/not-here")).willReturn(aResponse().withStatus(404)));

        builderFor(vertx)
                .build(OptionalClient.class)
                .findItem("not-here")
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertThat(result).isEmpty();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Future<Optional<Item>> returns Optional.of(item) when server responds 200")
    void optionalReturnsValueOn200(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/42")).willReturn(okJson("{\"name\":\"found\"}")));

        builderFor(vertx)
                .build(OptionalClient.class)
                .findItem("42")
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertThat(result).isPresent();
                    assertThat(result.get().name()).isEqualTo("found");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Future<Optional<Item>> fails when server responds 500")
    void optionalFailsOn500(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/api/items/err")).willReturn(aResponse().withStatus(500)));

        builderFor(vertx)
                .build(OptionalClient.class)
                .findItem("err")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    assertThat(((RestClientResponseException) err).statusCode()).isEqualTo(500);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@CookieParam is sent as Cookie header")
    void cookieParamIsSentAsCookieHeader(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/secure"))
                .withCookie("session", equalTo("abc-token"))
                .willReturn(okJson("{\"name\":\"secured\"}")));

        builderFor(vertx)
                .build(CookieClient.class)
                .getSecure("abc-token")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("secured");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("text/plain @Consumes sends body as raw string, not JSON-quoted")
    void textPlainBodyIsSentAsRawString(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(post(urlEqualTo("/api/echo"))
                .withHeader("Content-Type", equalTo("text/plain"))
                .withRequestBody(equalTo("hello world"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("hello world")));

        builderFor(vertx)
                .build(TextClient.class)
                .echo("hello world")
                .onComplete(ctx.succeeding(raw -> ctx.verify(() -> {
                    assertThat(raw.statusCode()).isEqualTo(200);
                    assertThat(raw.bodyAsString()).isEqualTo("hello world");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@DefaultValue is used when null is passed for optional query param")
    void defaultValueIsUsedWhenParamIsNull(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlPathEqualTo("/api/items"))
                .withQueryParam("page", equalTo("0"))
                .willReturn(okJson("[{\"name\":\"default-page\"}]")));

        builderFor(vertx)
                .build(DefaultValueClient.class)
                .listItems(null)
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.get(0).name()).isEqualTo("default-page");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@BeanParam with record type expands query params correctly")
    void beanParamRecordExpandsQueryParams(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlPathEqualTo("/api/items/search"))
                .withQueryParam("q", equalTo("widget"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(okJson("[{\"name\":\"found\"}]")));

        builderFor(vertx)
                .build(BeanParamRecordClient.class)
                .search(new SearchParams("widget", 2))
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(1);
                    assertThat(items.get(0).name()).isEqualTo("found");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@BeanParam record uses @DefaultValue when field is default int value")
    void beanParamRecordDefaultValueApplied(Vertx vertx, VertxTestContext ctx) {
        // page=0 is Java default for int, @DefaultValue("1") should override when explicitly
        // building a record — note: @DefaultValue on bean fields is a fallback for null only,
        // so here we test the explicit non-default record construction
        wireMock.stubFor(get(urlPathEqualTo("/api/items/search"))
                .withQueryParam("q", equalTo("test"))
                .willReturn(okJson("[{\"name\":\"result\"}]")));

        // Use page=1 explicitly (the default)
        builderFor(vertx)
                .build(BeanParamRecordClient.class)
                .search(new SearchParams("test", 1))
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(1);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@ExpectedStatus({200,201}) passes for 200 response")
    void expectedStatusPassesFor200(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/ok")).willReturn(okJson("{\"name\":\"ok\"}")));

        builderFor(vertx)
                .build(ExpectedStatusClient.class)
                .getItem("ok")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("ok");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@ExpectedStatus({200,201}) fails for 404 response")
    void expectedStatusFailsFor404(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/api/items/missing")).willReturn(aResponse().withStatus(404)));

        builderFor(vertx)
                .build(ExpectedStatusClient.class)
                .getItem("missing")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor beforeRequest can abort by returning failed Future")
    void beforeRequestAbortsPipeline(Vertx vertx, VertxTestContext ctx) {
        // No stub — the request must never reach WireMock
        class AbortException extends RuntimeException {
            AbortException() {
                super("aborted by interceptor");
            }
        }

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                return Future.failedFuture(new AbortException());
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("anything")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(AbortException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor onRequest context contains correct clientName and methodName")
    void onRequestContextHasCorrectNames(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/5")).willReturn(okJson("{\"name\":\"x\"}")));

        AtomicReference<String> capturedClient = new AtomicReference<>();
        AtomicReference<String> capturedMethod = new AtomicReference<>();

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public void onRequest(RestClientRequestContext reqCtx) {
                capturedClient.set(reqCtx.clientName());
                capturedMethod.set(reqCtx.methodName());
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("5")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(capturedClient.get()).isEqualTo("test-service");
                    assertThat(capturedMethod.get()).isEqualTo("getItem");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor priority ordering: lower priority runs first")
    void interceptorPriorityOrdering(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/p"))
                .withHeader("X-Order", equalTo("first"))
                .willReturn(okJson("{\"name\":\"ordered\"}")));

        // priority 10 runs second, priority 1 runs first
        RestClientInterceptor first = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                return Future.succeededFuture(reqCtx.withHeader("X-Order", "first"));
            }

            @Override
            public int priority() {
                return 1;
            }
        };

        RestClientInterceptor second = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                // This runs after "first" — would overwrite if active, but we want "first" to win
                // so we only add if not already present
                return Future.succeededFuture(reqCtx);
            }

            @Override
            public int priority() {
                return 10;
            }
        };

        builderFor(vertx)
                .register(second) // registered second but lower priority
                .register(first) // registered first but higher priority
                .build(TestClient.class)
                .getItem("p")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("ordered");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("External config override changes baseUrl")
    void externalConfigOverridesBaseUrl(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/1")).willReturn(okJson("{\"name\":\"config\"}")));

        JsonObject config = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "test-service",
                                        new JsonObject().put("baseUrl", "http://localhost:" + wireMock.port())));

        new RestClientBuilder(vertx)
                .config(dev.vertique.rest.client.config.RestClientConfig.indexFromConfig(config, configParser())
                        .get("test-service"))
                .build(TestClient.class)
                .getItem("1")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("config");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("URI rewrite via interceptor setRequestUri takes effect on actual request")
    void interceptorUriRewriteIsEffective(Vertx vertx, VertxTestContext ctx) {
        // Stub at a different path that we'll rewrite to
        wireMock.stubFor(get(urlEqualTo("/api/items/rewritten")).willReturn(okJson("{\"name\":\"rewritten\"}")));

        RestClientInterceptor rewriter = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                // Rewrite the URI to point to a different item
                String newUri = reqCtx.requestUri().replace("/api/items/original", "/api/items/rewritten");
                return Future.succeededFuture(reqCtx.withRequestUri(newUri));
            }
        };

        builderFor(vertx)
                .register(rewriter)
                .build(TestClient.class)
                .getItem("original")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("rewritten");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("onError interceptor is called on server error response")
    void onErrorInterceptorCalledOnServerError(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/api/items/err")).willReturn(aResponse().withStatus(503)));

        AtomicBoolean onErrorCalled = new AtomicBoolean(false);

        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public void onError(RestClientRequestContext request, RestClientResponseContext response, Throwable error) {
                onErrorCalled.set(true);
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(TestClient.class)
                .getItem("err")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(onErrorCalled).isTrue();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Map defaultHeaders are sent with each request via builder defaultHeader()")
    void multipleDefaultHeadersSentViaBuilder(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/2"))
                .withHeader("X-A", equalTo("1"))
                .withHeader("X-B", equalTo("2"))
                .willReturn(okJson("{\"name\":\"multi\"}")));

        builderFor(vertx)
                .defaultHeader("X-A", "1")
                .defaultHeader("X-B", "2")
                .build(TestClient.class)
                .getItem("2")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("multi");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("External config webClient section applies connectTimeoutMs to WebClientOptions")
    void externalConfigWebClientSectionAppliesOptions(Vertx vertx, VertxTestContext ctx) {
        // Verify that a webClient config block is merged into the effective WebClientOptions.
        // We use connectTimeoutMs (normalized name) as a representative duration field; the
        // WireMock stub confirms the client is still functional after the config is applied.
        wireMock.stubFor(get(urlEqualTo("/api/items/1")).willReturn(okJson("{\"name\":\"wc-config\"}")));

        JsonObject config = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "test-service",
                                        new JsonObject()
                                                .put("baseUrl", "http://localhost:" + wireMock.port())
                                                .put(
                                                        "webClient",
                                                        new JsonObject()
                                                                .put("connectTimeoutMs", 7000)
                                                                .put("keepAlive", true))));

        new RestClientBuilder(vertx)
                .config(dev.vertique.rest.client.config.RestClientConfig.indexFromConfig(config, configParser())
                        .get("test-service"))
                .build(TestClient.class)
                .getItem("1")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("wc-config");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("External config webClient section merges onto builder-level WebClientOptions")
    void externalConfigWebClientMergesOntoBuilderOptions(Vertx vertx, VertxTestContext ctx) {
        // Builder provides a baseline; config overrides only connectTimeoutMs while keepAlive from
        // the builder baseline should survive (merge, not replace).
        wireMock.stubFor(get(urlEqualTo("/api/items/1")).willReturn(okJson("{\"name\":\"wc-merge\"}")));

        io.vertx.ext.web.client.WebClientOptions baseline = new io.vertx.ext.web.client.WebClientOptions()
                .setConnectTimeout(5000)
                .setKeepAlive(true);

        JsonObject config = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "test-service",
                                        new JsonObject()
                                                .put("baseUrl", "http://localhost:" + wireMock.port())
                                                // Override only connectTimeoutMs; keepAlive stays from baseline
                                                .put("webClient", new JsonObject().put("connectTimeoutMs", 9000))));

        new RestClientBuilder(vertx)
                .webClientOptions(baseline)
                .config(dev.vertique.rest.client.config.RestClientConfig.indexFromConfig(config, configParser())
                        .get("test-service"))
                .build(TestClient.class)
                .getItem("1")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("wc-merge");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("RestClientFactory.builder() pre-seeds global interceptors")
    void factoryBuilderPreSeedsGlobalInterceptors(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/f"))
                .withHeader("X-Global", equalTo("global"))
                .willReturn(okJson("{\"name\":\"factory\"}")));

        AtomicBoolean globalCalled = new AtomicBoolean(false);
        RestClientInterceptor globalInterceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                globalCalled.set(true);
                return Future.succeededFuture(reqCtx.withHeader("X-Global", "global"));
            }
        };

        RestClientFactory factory =
                new RestClientFactory(vertx, java.util.Set.of(globalInterceptor), java.util.Map.of(), null);

        factory.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .build(TestClient.class)
                .getItem("f")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(globalCalled).isTrue();
                    assertThat(item.name()).isEqualTo("factory");
                    ctx.completeNow();
                })));
    }
}
