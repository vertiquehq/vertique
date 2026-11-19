// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientTimeoutException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration proof that a timeout or an open circuit on the re-dispatch that follows an accepted
 * {@link RestClientInterceptor#recoverRequest} reaches the caller as a REST client exception type.
 *
 * <p>The first request a client sends in a fresh JVM pays one-time costs (class loading, the WireMock
 * server's first exchange) that can exceed the 300 ms timeout on a busy machine. The timeout case
 * therefore warms its client up before the measured call, checks that the 401 was recovered, and
 * waits for the re-dispatch to reach the server instead of reading the journal at the moment of the
 * timeout. The circuit case counts the requests of its own path only, so that a late request of the
 * timeout case never reaches its count.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientRecoveryFailureMappingIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                    .dynamicPort()
                    .bindAddress("127.0.0.1"))
            .build();

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @RestClient(name = "recover-timeout-client")
    interface RecoverTimeoutClient {

        @dev.vertique.resilience.annotation.Timeout(300)
        @GET
        @Path("/recover-timeout")
        Future<String> call();

        /** A request outside the timeout that leaves the client and the server warm. */
        @GET
        @Path("/warm-up")
        Future<String> warmUp();
    }

    @RestClient(name = "recover-circuit-client")
    interface RecoverCircuitClient {

        @CircuitBreaker(maxFailures = 1, timeoutMs = 1_000, resetTimeoutMs = 30_000)
        @GET
        @Path("/recover-circuit")
        Future<String> call();
    }

    /** 401 exercises the timeout case; 503 is a status the circuit breaker counts as a failure. */
    private static boolean isRecoverable(int statusCode) {
        return statusCode == 401 || statusCode == 503;
    }

    /** Recoveries the interceptor accepted in the current test, each followed by a re-dispatch. */
    private final AtomicInteger acceptedRecoveries = new AtomicInteger();

    /** Turns a recoverable status into a failure and accepts it for recovery with a refreshed header. */
    private final RestClientInterceptor authRefresher = new RestClientInterceptor() {
        @Override
        public Future<Void> afterResponse(RestClientRequestContext request, RestClientResponseContext response) {
            if (isRecoverable(response.statusCode())) {
                return Future.failedFuture(new RestClientResponseException(request, response));
            }
            return Future.succeededFuture();
        }

        @Override
        public Future<RestClientRequestContext> recoverRequest(
                RestClientRequestContext request, @Nullable RestClientResponseContext response, Throwable error) {
            if (error instanceof RestClientResponseException ex && isRecoverable(ex.statusCode())) {
                acceptedRecoveries.incrementAndGet();
                return Future.succeededFuture(request.withHeader("Authorization", "Bearer refreshed"));
            }
            return Future.failedFuture(error);
        }
    };

    private RestClientBuilder builder(Vertx vertx) {
        return RestClientBuilder.create(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .register(authRefresher);
    }

    @Test
    void recoveryRedispatchTimeoutSurfacesAsRestTimeout(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(
                get(urlEqualTo("/recover-timeout")).willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo("/recover-timeout"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        wireMock.stubFor(get(urlEqualTo("/warm-up")).willReturn(aResponse().withStatus(200)));
        RestClientBuilder builder = builder(vertx);
        RecoverTimeoutClient client = builder.build(RecoverTimeoutClient.class);

        client.warmUp()
                .onComplete(testContext.succeeding(warm -> client.call().onComplete(testContext.failing(failure -> {
                    testContext.verify(() -> {
                        assertThat(failure).isExactlyInstanceOf(RestClientTimeoutException.class);
                        assertThat(failure.getMessage()).isEqualTo("REST client request timed out");
                        assertThat(failure.getCause()).isInstanceOf(ResilienceTimeoutException.class);
                        // The 401 was answered and recovered, so the timeout is the re-dispatch's, not the
                        // first attempt's.
                        assertThat(acceptedRecoveries).hasValue(1);
                    });
                    // The re-dispatch reaches the server's journal on its own schedule, possibly after the
                    // timeout fired; wait for it rather than read the journal at the moment of the timeout.
                    refreshedRequests(vertx).onComplete(testContext.succeeding(refreshed -> {
                        testContext.verify(() -> assertThat(refreshed).isEqualTo(1));
                        builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
                    }));
                }))));
    }

    /**
     * Waits, off the event loop and for at most ten seconds, until the re-dispatch with the refreshed
     * header has reached the server, and answers how many such requests the server has seen.
     */
    private static Future<Integer> refreshedRequests(Vertx vertx) {
        return vertx.executeBlocking(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int seen = refreshedRequestCount();
            while (seen == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
                seen = refreshedRequestCount();
            }
            return seen;
        });
    }

    private static int refreshedRequestCount() {
        return wireMock.findAll(getRequestedFor(urlEqualTo("/recover-timeout"))
                        .withHeader("Authorization", equalTo("Bearer refreshed")))
                .size();
    }

    @Test
    void recoveryRedispatchOpenCircuitSurfacesAsUnavailable(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(
                get(urlEqualTo("/recover-circuit")).willReturn(aResponse().withStatus(503)));
        RestClientBuilder builder = builder(vertx);

        builder.build(RecoverCircuitClient.class).call().onComplete(testContext.failing(failure -> {
            testContext.verify(() -> {
                assertThat(failure).isExactlyInstanceOf(RestClientUnavailableException.class);
                assertThat(failure.getMessage()).isEqualTo("REST client circuit is open");
                assertThat(failure.getCause()).isInstanceOf(CircuitOpenException.class);
                // The re-dispatch was refused by the open circuit, so only the first attempt reached the server.
                assertThat(acceptedRecoveries).hasValue(1);
                assertThat(wireMock.findAll(getRequestedFor(urlEqualTo("/recover-circuit"))))
                        .hasSize(1);
            });
            builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
        }));
    }
}
