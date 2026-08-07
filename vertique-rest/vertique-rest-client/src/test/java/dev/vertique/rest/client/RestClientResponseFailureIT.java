// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.rest.client.exception.RestClientResponseException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Parameterized integration tests for HTTP-level response failures.
 *
 * <p>Each {@link KnownIntegrationFailure} scenario configures WireMock to return a specific
 * problematic HTTP response and asserts that the REST client proxy throws the expected exception.
 * Additional focused tests cover {@code problem+json} body access and status code checks.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientResponseFailureIT {

    // --- Test DTOs ---

    /** Generic item DTO for deserialization tests. */
    record Item(String name) {}

    // --- Minimal client interface ---

    @RestClient(name = "response-failure-test")
    @Path("/test")
    @Produces("application/json")
    interface ResponseFailureClient {
        /** Simple GET method returning a typed item. */
        @GET
        Future<Item> get();
    }

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Resets all WireMock stubs and serve-events before each test. The server stays up for the
     * whole class: restarting it per test races stub registration against in-flight dispatch
     * under full-suite load, which surfaces as unexplained 404s.
     */
    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    /**
     * Builds a {@link ResponseFailureClient} pointing at the WireMock server.
     *
     * @param vertx the Vert.x instance
     * @return a ready-to-use test client
     */
    private ResponseFailureClient buildClient(Vertx vertx) {
        return new RestClientBuilder(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .build(ResponseFailureClient.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(KnownIntegrationFailure.class)
    @DisplayName("Response failure scenario fails with expected exception type")
    void responseFailureScenario(KnownIntegrationFailure scenario, Vertx vertx, VertxTestContext ctx) {
        scenario.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err)
                            .as(
                                    "scenario %s: expected %s but got %s",
                                    scenario.name(),
                                    scenario.expectedExceptionType().getSimpleName(),
                                    err.getClass().getSimpleName())
                            .isInstanceOf(scenario.expectedExceptionType());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PROBLEM_JSON_400 exception carries parseable problem detail body")
    void problemJson400HasParseableProblemDetail(Vertx vertx, VertxTestContext ctx) {
        KnownIntegrationFailure.PROBLEM_JSON_400.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(400);
                    assertThat(ex.isClientError()).isTrue();

                    // Verify problem detail is parseable as a Map (avoids dependency on ProblemDetail class)
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pd = ex.bodyAs(Map.class, MAPPER);
                    assertThat(pd).containsKey("title");
                    assertThat(pd.get("title")).isEqualTo("Bad Request");
                    assertThat(pd.get("status")).isEqualTo(400);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("PROBLEM_JSON_409 exception has status 409 and parseable body")
    void problemJson409HasStatus409(Vertx vertx, VertxTestContext ctx) {
        KnownIntegrationFailure.PROBLEM_JSON_409.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(409);
                    assertThat(ex.isClientError()).isTrue();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("SERVER_ERROR_JSON exception has isServerError()==true")
    void serverErrorJsonIsServerError(Vertx vertx, VertxTestContext ctx) {
        KnownIntegrationFailure.SERVER_ERROR_JSON.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.isServerError()).isTrue();
                    assertThat(ex.isClientError()).isFalse();
                    assertThat(ex.statusCode()).isEqualTo(500);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("RestClientResponseException carries request context with clientName and methodName")
    void responseExceptionHasRequestContext(Vertx vertx, VertxTestContext ctx) {
        KnownIntegrationFailure.STANDARD_404.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.requestContext()).isNotNull();
                    assertThat(ex.requestContext().clientName()).isEqualTo("response-failure-test");
                    assertThat(ex.requestContext().methodName()).isEqualTo("get");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("GATEWAY_TIMEOUT exception has status 504 and isServerError()==true")
    void gatewayTimeoutIsServerError(Vertx vertx, VertxTestContext ctx) {
        KnownIntegrationFailure.GATEWAY_TIMEOUT.prepare(wireMock);
        ResponseFailureClient client = buildClient(vertx);

        client.get()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientResponseException.class);
                    RestClientResponseException ex = (RestClientResponseException) err;
                    assertThat(ex.statusCode()).isEqualTo(504);
                    assertThat(ex.isServerError()).isTrue();
                    ctx.completeNow();
                })));
    }
}
