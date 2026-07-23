// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link DefaultRestClientDispatcher} verifying that the pipeline
 * (interceptors, resilience, exception mapping, response decoding) works correctly through
 * the new dispatcher abstraction.
 *
 * <p>These tests use WireMock as the HTTP server and drive the full pipeline via {@link
 * RestClientBuilder}, which creates a {@link RestClientProxy} backed by the dispatcher.
 * The tests verify that behaviour is identical to the pre-refactor path.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefaultRestClientDispatcherIT {

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

    /** A simple item DTO. */
    record Item(String name) {}

    // --- Test client interface ---

    @RestClient(name = "dispatcher-test")
    @Path("/api")
    @Produces("application/json")
    @Consumes("application/json")
    interface DispatcherTestClient {

        @GET
        @Path("/items/{id}")
        Future<Item> getItem(@PathParam("id") String id);

        @POST
        @Path("/items")
        Future<Item> createItem(Item body);

        @GET
        @Path("/items")
        Future<List<Item>> listItems();
    }

    // --- Builder helper ---

    private RestClientBuilder builderFor(Vertx vertx) {
        return new RestClientBuilder(vertx).baseUrl("http://localhost:" + wireMock.port());
    }

    // --- Tests ---

    @Test
    @DisplayName("GET via dispatcher deserializes a JSON object response")
    void dispatcherDeserializesJsonObject(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/1")).willReturn(okJson("{\"name\":\"thing\"}")));

        builderFor(vertx)
                .build(DispatcherTestClient.class)
                .getItem("1")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("thing");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("GET via dispatcher deserializes a JSON array response")
    void dispatcherDeserializesJsonArray(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items")).willReturn(okJson("[{\"name\":\"a\"},{\"name\":\"b\"}]")));

        builderFor(vertx)
                .build(DispatcherTestClient.class)
                .listItems()
                .onComplete(ctx.succeeding(items -> ctx.verify(() -> {
                    assertThat(items).hasSize(2);
                    assertThat(items).extracting(Item::name).containsExactly("a", "b");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("POST via dispatcher sends body and deserializes response")
    void dispatcherSendsBodyAndDeserializes(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(post(urlEqualTo("/api/items"))
                .withRequestBody(equalTo("{\"name\":\"new\"}"))
                .willReturn(okJson("{\"name\":\"new\"}")));

        builderFor(vertx)
                .build(DispatcherTestClient.class)
                .createItem(new Item("new"))
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item.name()).isEqualTo("new");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("404 response fails the future via the dispatcher error pipeline")
    void dispatcherFailsOnNonSuccessStatus(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/missing"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        builderFor(vertx)
                .build(DispatcherTestClient.class)
                .getItem("missing")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    assertThat(((RestClientResponseException) err).statusCode()).isEqualTo(404);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Interceptor beforeRequest mutates headers through the dispatcher pipeline")
    void interceptorMutatesHeadersThroughDispatcher(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/api/items/2"))
                .withHeader("X-Test", equalTo("dispatcher"))
                .willReturn(okJson("{\"name\":\"intercepted\"}")));

        AtomicBoolean interceptorCalled = new AtomicBoolean(false);
        RestClientInterceptor interceptor = new RestClientInterceptor() {
            @Override
            public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext reqCtx) {
                interceptorCalled.set(true);
                return Future.succeededFuture(reqCtx.withHeader("X-Test", "dispatcher"));
            }
        };

        builderFor(vertx)
                .register(interceptor)
                .build(DispatcherTestClient.class)
                .getItem("2")
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(interceptorCalled).isTrue();
                    assertThat(item.name()).isEqualTo("intercepted");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Exception mapper translates transport failure via the dispatcher")
    void exceptionMapperTranslatesFailureThroughDispatcher(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/api/items/fail")).willReturn(aResponse().withStatus(503)));

        class ServiceUnavailable extends RuntimeException {
            ServiceUnavailable() {
                super("service unavailable");
            }
        }

        builderFor(vertx)
                .onFailure(RestClientResponseException.class, ex -> new ServiceUnavailable())
                .build(DispatcherTestClient.class)
                .getItem("fail")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(ServiceUnavailable.class);
                    ctx.completeNow();
                })));
    }
}
