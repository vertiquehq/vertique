// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.vertique.resilience.annotation.Bulkhead;
import dev.vertique.resilience.exception.BulkheadQueueTimeoutException;
import dev.vertique.resilience.exception.BulkheadRejectedException;
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

/** Integration proof that bulkhead rejections reach the caller as {@link RestClientUnavailableException}. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientBulkheadFailureMappingIT {

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

    @RestClient(name = "bulkhead-reject-client")
    interface RejectingBulkheadClient {

        @Bulkhead(maxConcurrentCalls = 1)
        @GET
        @Path("/slow")
        Future<String> slow();
    }

    @RestClient(name = "bulkhead-queue-client")
    interface QueueingBulkheadClient {

        @Bulkhead(maxConcurrentCalls = 1, mode = Bulkhead.Mode.QUEUE, maxQueueSize = 1, queueTimeoutMs = 100)
        @GET
        @Path("/slow")
        Future<String> slow();
    }

    private RestClientBuilder builder(Vertx vertx) {
        return RestClientBuilder.create(vertx).baseUrl("http://127.0.0.1:" + wireMock.getPort());
    }

    @Test
    void bulkheadRejectionSurfacesAsUnavailable(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(
                get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)));
        RestClientBuilder builder = builder(vertx);
        RejectingBulkheadClient client = builder.build(RejectingBulkheadClient.class);

        Future<String> inFlight = client.slow();
        client.slow().onComplete(testContext.failing(rejected -> {
            testContext.verify(() -> {
                assertThat(rejected).isExactlyInstanceOf(RestClientUnavailableException.class);
                assertThat(rejected.getMessage()).isEqualTo("REST client concurrency limit reached");
                assertThat(rejected.getCause()).isInstanceOf(BulkheadRejectedException.class);
            });
            inFlight.onComplete(
                    ignored -> builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow())));
        }));
    }

    @Test
    void bulkheadQueueTimeoutSurfacesAsUnavailable(Vertx vertx, VertxTestContext testContext) {
        wireMock.stubFor(
                get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)));
        RestClientBuilder builder = builder(vertx);
        QueueingBulkheadClient client = builder.build(QueueingBulkheadClient.class);

        Future<String> inFlight = client.slow();
        client.slow().onComplete(testContext.failing(timedOut -> {
            testContext.verify(() -> {
                assertThat(timedOut).isExactlyInstanceOf(RestClientUnavailableException.class);
                assertThat(timedOut.getMessage()).isEqualTo("REST client queue wait timed out");
                assertThat(timedOut.getCause()).isInstanceOf(BulkheadQueueTimeoutException.class);
            });
            inFlight.onComplete(
                    ignored -> builder.close().onComplete(testContext.succeeding(v -> testContext.completeNow())));
        }));
    }
}
