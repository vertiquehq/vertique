// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.exception.RestClientUnavailableException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Integration proof for REST status mapping and client-local shared breaker state. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientSharedResilienceIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                    .dynamicPort()
                    .bindAddress("127.0.0.1"))
            .build();

    @CircuitBreaker(maxFailures = 1, timeoutMs = 1_000, resetTimeoutMs = 30_000)
    @RestClient(name = "shared-resilience-client")
    interface SharedResilienceClient {

        @GET
        @Path("/server-error")
        Future<String> serverError();

        @GET
        @Path("/other-server-error")
        Future<String> otherServerError();

        @GET
        @Path("/rate-limited")
        Future<String> rateLimited();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void appliesLegacyMatrixAndFailureSeams(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(get(urlEqualTo("/server-error")).willReturn(aResponse().withStatus(502)));
        wireMock.stubFor(
                get(urlEqualTo("/other-server-error")).willReturn(aResponse().withStatus(502)));
        wireMock.stubFor(get(urlEqualTo("/rate-limited")).willReturn(aResponse().withStatus(429)));

        RestClientBuilder builder = RestClientBuilder.create(vertx).baseUrl("http://127.0.0.1:" + wireMock.getPort());
        SharedResilienceClient first = builder.build(SharedResilienceClient.class);
        SharedResilienceClient second = builder.build(SharedResilienceClient.class);

        first.rateLimited().onComplete(testContext.failing(rateLimitFailure -> {
            first.serverError().onComplete(testContext.failing(serverFailure -> {
                first.otherServerError().onComplete(testContext.failing(sharedBreakerFailure -> {
                    second.serverError().onComplete(testContext.failing(secondFailure -> {
                        builder.close().onComplete(testContext.succeeding(ignored -> {
                            testContext.verify(() -> {
                                assertThat(rateLimitFailure).isInstanceOf(RestClientResponseException.class);
                                assertThat(((RestClientResponseException) rateLimitFailure).statusCode())
                                        .isEqualTo(429);
                                assertThat(serverFailure).isInstanceOf(RestClientResponseException.class);
                                assertThat(sharedBreakerFailure).isInstanceOf(RestClientUnavailableException.class);
                                assertThat(secondFailure).isInstanceOf(RestClientResponseException.class);
                                assertThat(wireMock.getAllServeEvents()).hasSize(3);
                                testContext.completeNow();
                            });
                        }));
                    }));
                }));
            }));
        }));
    }
}
