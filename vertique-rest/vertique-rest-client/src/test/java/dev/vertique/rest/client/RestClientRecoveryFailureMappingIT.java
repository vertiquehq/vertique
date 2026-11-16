// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration proof that a timeout or an open circuit on the re-dispatch that follows an accepted
 * {@link RestClientInterceptor#recoverRequest} reaches the caller as a REST client exception type.
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

    /** Turns a recoverable status into a failure and accepts it for recovery with a refreshed header. */
    private static final RestClientInterceptor AUTH_REFRESHER = new RestClientInterceptor() {
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
                return Future.succeededFuture(request.withHeader("Authorization", "Bearer refreshed"));
            }
            return Future.failedFuture(error);
        }
    };

    private RestClientBuilder builder(Vertx vertx) {
        return RestClientBuilder.create(vertx)
                .baseUrl("http://127.0.0.1:" + wireMock.getPort())
                .register(AUTH_REFRESHER);
    }

    @Test
    void recoveryRedispatchTimeoutSurfacesAsRestTimeout(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(
                get(urlEqualTo("/recover-timeout")).willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo("/recover-timeout"))
                .withHeader("Authorization", equalTo("Bearer refreshed"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        RestClientBuilder builder = builder(vertx);

        builder.build(RecoverTimeoutClient.class).call().onComplete(testContext.failing(failure -> {
            testContext.verify(() -> {
                assertThat(failure).isExactlyInstanceOf(RestClientTimeoutException.class);
                assertThat(failure.getMessage()).isEqualTo("REST client request timed out");
                assertThat(failure.getCause()).isInstanceOf(ResilienceTimeoutException.class);
                assertThat(wireMock.getAllServeEvents()).hasSize(2);
            });
            builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
        }));
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
                assertThat(wireMock.getAllServeEvents()).hasSize(1);
            });
            builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow()));
        }));
    }
}
