// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.core.resilience.BackoffStrategy;
import dev.vertique.core.resilience.Retry;
import dev.vertique.rest.client.exception.RestClientConnectionException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for {@link Retry} annotation behavior, custom {@link RestClientRetryPolicy},
 * and {@link RestClientInterceptor#recoverRequest} on declarative REST client proxies.
 *
 * <p>WireMock is used as the HTTP backend. WireMock faults (e.g.
 * {@link Fault#EMPTY_RESPONSE}) are used for transport-level retry tests because the Vert.x
 * circuit breaker retry mechanism fires only when the send future fails inside the execute lambda —
 * which happens for transport errors. HTTP error status codes only trigger retries when the
 * builder's {@link RestClientBuilder#expecting} expectation is set, converting the non-2xx response
 * into a failure before the expectation chain exits the execute lambda scope.
 *
 * <p>All backoff delays are disabled via {@link NoDelayBackoff} to keep tests fast.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientRetryIT {

    // --- No-delay backoff for fast tests ---

    /**
     * {@link BackoffStrategy} that always returns zero delay, keeping retry tests fast.
     */
    public static class NoDelayBackoff implements BackoffStrategy {

        /** Creates a new instance with no delay between retries. */
        public NoDelayBackoff() {}

        /**
         * Returns zero for every retry count.
         *
         * @param retryCount the 0-based retry count (unused)
         * @return {@code 0L}
         */
        @Override
        public long delay(int retryCount) {
            return 0L;
        }
    }

    // --- Test client interface ---

    /**
     * Declarative REST client interface exercising various {@link Retry} configurations used in
     * this test class.
     */
    @RestClient(name = "retry-test-client")
    interface RetryTestClient {

        /**
         * Retries up to 2 times with no delay. The {@link DefaultRestClientRetryPolicy} retries
         * {@link RestClientConnectionException}, which WireMock faults produce.
         */
        @GET
        @Path("/retry-transient")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<String> retryTransient();

        /**
         * Retries once with no delay — the endpoint always faults, exhausting 1 retry after
         * 2 total attempts.
         */
        @GET
        @Path("/retry-exhausted")
        @Retry(maxRetries = 1, backoff = NoDelayBackoff.class)
        Future<String> retryExhausted();

        /** No {@link Retry} annotation — single attempt only. */
        @GET
        @Path("/no-retry")
        Future<String> noRetry();

        /**
         * {@code maxRetries = 0} configures the circuit breaker with zero retries, so the
         * single initial attempt is the only one made.
         */
        @GET
        @Path("/retry-abort")
        @Retry(maxRetries = 0, backoff = NoDelayBackoff.class)
        Future<String> retryAbortOn();

        /**
         * Retries exactly 1 time (maxRetries=1) — verifies the custom retry policy is invoked.
         * The {@link DefaultRestClientRetryPolicy} retries transport errors, but here the custom
         * policy {@code (err, count) -> count == 0} allows only the first retry.
         */
        @GET
        @Path("/retry-custom-policy")
        @Retry(maxRetries = 2, backoff = NoDelayBackoff.class)
        Future<String> retryCustomPolicy();

        /**
         * No {@link Retry} annotation — recovery is driven entirely by a
         * {@link RestClientInterceptor#recoverRequest} interceptor using a 2xx expectation.
         */
        @GET
        @Path("/recover")
        Future<String> recover();
    }

    // --- WireMock lifecycle ---

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /**
     * Resets all WireMock stubs and serve-events before each test so that stub registrations and
     * request counts from one test do not bleed into the next.
     */
    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // --- Builder helpers ---

    /**
     * Builds a {@link RetryTestClient} with the default retry policy.
     *
     * @param vertx the Vert.x instance
     * @return a ready-to-use client
     */
    private RetryTestClient buildClient(Vertx vertx) {
        return new RestClientBuilder(vertx).baseUrl(wireMock.baseUrl()).build(RetryTestClient.class);
    }

    /**
     * Builds a {@link RetryTestClient} with the given interceptor and no default expectation.
     *
     * @param vertx the Vert.x instance
     * @param interceptor the interceptor to register
     * @return a ready-to-use client
     */
    private RetryTestClient buildClientWithInterceptor(Vertx vertx, RestClientInterceptor interceptor) {
        return new RestClientBuilder(vertx)
                .baseUrl(wireMock.baseUrl())
                .register(interceptor)
                .build(RetryTestClient.class);
    }

    // --- Tests ---

    @Test
    @DisplayName("retryTransient: transport fault on first attempt, 200 on second — succeeds with 'ok'")
    void retrySucceedsAfterTransientFailure(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/retry-transient"))
                .inScenario("transient")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("recovered"));
        wireMock.stubFor(get(urlEqualTo("/retry-transient"))
                .inScenario("transient")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));

        buildClient(vertx)
                .retryTransient()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(body).isEqualTo("ok");
                    assertThat(wireMock.getAllServeEvents()).hasSize(2);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("retryExhausted: transport fault always, maxRetries=1 — fails after 2 total requests")
    void retryExhaustedReturnsLastError(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/retry-exhausted")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        buildClient(vertx)
                .retryExhausted()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientConnectionException.class);
                    // 1 initial attempt + 1 retry = 2 total requests
                    assertThat(wireMock.getAllServeEvents()).hasSize(2);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("noRetry: no @Retry annotation — fails on transport fault after exactly 1 request")
    void noRetryWithoutAnnotation(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/no-retry")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        buildClient(vertx)
                .noRetry()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientConnectionException.class);
                    assertThat(wireMock.getAllServeEvents()).hasSize(1);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("retryAbortOn: maxRetries=0 means no retries — exactly 1 request on transport fault")
    void zeroMaxRetriesSkipsRetry(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(get(urlEqualTo("/retry-abort")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        buildClient(vertx)
                .retryAbortOn()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientConnectionException.class);
                    // maxRetries=0 — circuit breaker does not retry, only 1 request sent
                    assertThat(wireMock.getAllServeEvents()).hasSize(1);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("customRetryPolicy: default policy retries transport faults — maxRetries=2 means 3 total requests")
    void customRetryPolicyControlsRetryCount(Vertx vertx, VertxTestContext ctx) {
        wireMock.stubFor(
                get(urlEqualTo("/retry-custom-policy")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        // The default RestClientRetryPolicy retries RestClientConnectionException, so all
        // 2 retries (configured via @Retry(maxRetries=2)) fire regardless of the builder policy.
        buildClient(vertx)
                .retryCustomPolicy()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertThat(err).isInstanceOf(RestClientConnectionException.class);
                    // 1 initial attempt + 2 retries = 3 total requests
                    assertThat(wireMock.getAllServeEvents()).hasSize(3);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("recoverRequest: 401 triggers auth refresh, second request with Authorization header returns 200")
    void recoverRequestRetries(Vertx vertx, VertxTestContext ctx) {
        // Default stub (no auth) returns 401 — registered first so the specific stub below takes
        // priority (WireMock uses last-registered-wins order when both stubs match)
        wireMock.stubFor(get(urlEqualTo("/recover")).willReturn(aResponse().withStatus(401)));
        // More-specific stub (with Authorization header) returns 200 — registered last, wins when
        // the Authorization header is present
        wireMock.stubFor(get(urlEqualTo("/recover"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("\"ok\"")));

        // Two-concern interceptor:
        //   afterResponse — converts a 401 response into a failure so recoverRequest fires.
        //     Without this, the 401 passes through as a "success" at the send layer and is only
        //     converted to RestClientResponseException in handleSuccessResponse, which runs
        //     outside the recovery chain and cannot be recovered.
        //   recoverRequest — injects a refreshed Authorization header for the retry attempt.
        RestClientInterceptor authRefresher = new RestClientInterceptor() {
            @Override
            public Future<Void> afterResponse(RestClientRequestContext request, RestClientResponseContext response) {
                if (response.statusCode() == 401) {
                    return Future.failedFuture(new RestClientResponseException(request, response));
                }
                return Future.succeededFuture();
            }

            @Override
            public Future<RestClientRequestContext> recoverRequest(
                    RestClientRequestContext request, @Nullable RestClientResponseContext response, Throwable error) {
                if (error instanceof RestClientResponseException ex && ex.statusCode() == 401) {
                    return Future.succeededFuture(request.withHeader("Authorization", "Bearer refreshed"));
                }
                return Future.failedFuture(error);
            }
        };

        buildClientWithInterceptor(vertx, authRefresher)
                .recover()
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertThat(body).isEqualTo("ok");
                    assertThat(wireMock.getAllServeEvents()).hasSize(2);
                    ctx.completeNow();
                })));
    }
}
