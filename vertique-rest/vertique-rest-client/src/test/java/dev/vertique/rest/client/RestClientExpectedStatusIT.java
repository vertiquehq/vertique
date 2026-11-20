// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Accepted and rejected statuses: an expectation decides success whatever the status class, an
 * accepted response is decoded as the declared type, and a rejected one fails with
 * {@link RestClientResponseException}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientExpectedStatusIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    record Item(String name) {}

    @RestClient(name = "expected-status")
    @Path("/items")
    interface ItemClient {
        @GET
        @Path("/value")
        @ExpectedStatus({200, 404})
        Future<Item> value();

        @GET
        @Path("/optional")
        @ExpectedStatus({200, 404})
        Future<Optional<Item>> optional();

        @GET
        @Path("/void")
        @ExpectedStatus({200, 404})
        Future<Void> nothing();

        @GET
        @Path("/raw")
        @ExpectedStatus(min = 200, max = 600)
        Future<HttpClientResponse> raw();

        @GET
        @Path("/created")
        @ExpectedStatus(201)
        Future<Item> created();

        @GET
        @Path("/plain")
        Future<Item> plain();

        @GET
        @Path("/optional-plain")
        Future<Optional<Item>> optionalPlain();

        @GET
        @Path("/retried")
        @ExpectedStatus({200, 503})
        @Retry(maxRetries = 2, backoff = RestClientRetryIT.NoDelayBackoff.class)
        Future<Item> acceptedRetried();

        @GET
        @Path("/rejected-retried")
        @ExpectedStatus(200)
        @Retry(maxRetries = 2, backoff = RestClientRetryIT.NoDelayBackoff.class)
        Future<Item> rejectedRetried();
    }

    private RestClientBuilder builder(Vertx vertx) {
        return new RestClientBuilder(vertx).baseUrl("http://127.0.0.1:" + wireMock.getPort());
    }

    @Test
    @DisplayName("an accepted 404 decodes the declared type when the body fits it")
    void acceptedNotFoundDecodes(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/value"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"absent\"}")));

        builder(vertx)
                .build(ItemClient.class)
                .value()
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item).isEqualTo(new Item("absent"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an accepted 404 with a body that does not fit the declared type fails with a decoding error")
    void acceptedNotFoundMisfitFailsToDecode(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/value"))
                .willReturn(aResponse().withStatus(404).withBody("<html>gateway</html>")));

        builder(vertx)
                .build(ItemClient.class)
                .value()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isExactlyInstanceOf(RestClientException.class);
                    assertThat(err.getMessage()).contains("Failed to deserialize");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Optional is empty for a 404 and decoded for another accepted status")
    void optionalAcceptedStatuses(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/items/optional")).willReturn(aResponse().withStatus(404)));
        ItemClient client = builder(vertx).build(ItemClient.class);

        client.optional()
                .compose(missing -> {
                    assertThat(missing).isEmpty();
                    wireMock.stubFor(get(urlEqualTo("/items/optional")).willReturn(okJson("{\"name\":\"here\"}")));
                    return client.optional();
                })
                .onComplete(ctx.succeeding(found -> ctx.verify(() -> {
                    assertThat(found).contains(new Item("here"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Void and HttpClientResponse succeed for an accepted non-2xx status")
    void voidAndRawAcceptNonSuccess(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/void"))
                .willReturn(aResponse().withStatus(404).withBody("{\"title\":\"not found\"}")));
        wireMock.stubFor(get(urlEqualTo("/items/raw"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        ItemClient client = builder(vertx).build(ItemClient.class);

        client.nothing()
                .compose(v -> {
                    assertThat(v).isNull();
                    return client.raw();
                })
                .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
                    assertThat(response.statusCode()).isEqualTo(500);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a 2xx the expectation rejects fails with RestClientResponseException and keeps the reason")
    void rejectedSuccessStatusIsAResponseException(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/created")).willReturn(okJson("{\"name\":\"x\"}")));

        builder(vertx)
                .build(ItemClient.class)
                .created()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(200);
                    assertThat(ex.getCause()).isNotNull();
                    assertThat(ex.getMessage())
                            .startsWith("HTTP 200")
                            .contains(ex.getCause().getMessage());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("without an expectation a non-2xx status still fails with RestClientResponseException")
    void defaultRuleIsUnchanged(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/plain")).willReturn(aResponse().withStatus(404)));

        builder(vertx)
                .build(ItemClient.class)
                .plain()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    assertThat(((RestClientResponseException) err).statusCode()).isEqualTo(404);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a builder expectation does not accept a non-2xx status that it would pass")
    void builderExpectationDoesNotAcceptNonSuccess(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/plain"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"gone\"}")));

        builder(vertx)
                .expecting(HttpResponseExpectation.SC_SUCCESS.or(HttpResponseExpectation.SC_NOT_FOUND))
                .build(ItemClient.class)
                .plain()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    assertThat(((RestClientResponseException) err).statusCode()).isEqualTo(404);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a status-blind builder expectation still fails a 500 that matches it")
    void statusBlindExpectationKeepsTheStatusRule(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/plain"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"error body\"}")));

        builder(vertx)
                .expecting(HttpResponseExpectation.JSON)
                .build(ItemClient.class)
                .plain()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(500);
                    assertThat(ex.getCause()).isNotNull();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a builder expectation also rejects a 2xx it does not accept, keeping the reason")
    void builderExpectationRejectsMatchingStatusWithWrongShape(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/plain"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("not json")));

        builder(vertx)
                .expecting(HttpResponseExpectation.JSON)
                .build(ItemClient.class)
                .plain()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(200);
                    assertThat(ex.getCause()).isNotNull();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a 2xx that the builder expectation accepts succeeds")
    void builderExpectationAcceptsMatchingSuccess(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/plain")).willReturn(okJson("{\"name\":\"fine\"}")));

        builder(vertx)
                .expecting(HttpResponseExpectation.JSON)
                .build(ItemClient.class)
                .plain()
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item).isEqualTo(new Item("fine"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("@ExpectedStatus replaces the builder expectation and the 2xx rule for its method")
    void methodStatusesOutrankTheBuilderExpectation(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/value"))
                .willReturn(aResponse().withStatus(404).withBody("{\"name\":\"gone\"}")));

        builder(vertx)
                .expecting(HttpResponseExpectation.JSON)
                .build(ItemClient.class)
                .value()
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item).isEqualTo(new Item("gone"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Optional with a 404 is empty even when a builder expectation is set")
    void optionalNotFoundIsEmptyWithBuilderExpectation(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/items/optional-plain")).willReturn(aResponse().withStatus(404)));

        builder(vertx)
                .expecting(HttpResponseExpectation.JSON)
                .build(ItemClient.class)
                .optionalPlain()
                .onComplete(ctx.succeeding(item -> ctx.verify(() -> {
                    assertThat(item).isEmpty();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an accepted 503 is not retried; a rejected one is")
    void acceptedStatusIsNotRetried(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/retried"))
                .willReturn(okJson("{\"name\":\"a\"}").withStatus(503)));
        wireMock.stubFor(get(urlEqualTo("/items/rejected-retried"))
                .willReturn(aResponse().withStatus(503)));
        ItemClient client = builder(vertx).build(ItemClient.class);

        client.acceptedRetried()
                .compose(item -> {
                    wireMock.verify(1, getRequestedFor(urlEqualTo("/items/retried")));
                    return client.rejectedRetried().map(v -> (Throwable) null).recover(Future::succeededFuture);
                })
                .onComplete(ctx.succeeding(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    wireMock.verify(3, getRequestedFor(urlEqualTo("/items/rejected-retried")));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an accepted non-2xx fires onResponse and not onError; a rejected one fires onError only")
    void interceptorsSeeAcceptedAndRejected(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/items/void")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlEqualTo("/items/created")).willReturn(okJson("{\"name\":\"x\"}")));
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        RestClientInterceptor recorder = new RestClientInterceptor() {
            @Override
            public void onResponse(RestClientRequestContext request, RestClientResponseContext response) {
                events.add("response:" + response.statusCode());
            }

            @Override
            public void onError(RestClientRequestContext request, RestClientResponseContext response, Throwable error) {
                events.add("error:" + (response == null ? "none" : response.statusCode()));
            }
        };
        ItemClient client = builder(vertx).register(recorder).build(ItemClient.class);

        client.nothing()
                .compose(v -> client.created().map(i -> (Throwable) null).recover(Future::succeededFuture))
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertThat(events).containsExactly("response:404", "error:200");
                    ctx.completeNow();
                })));
    }
}
