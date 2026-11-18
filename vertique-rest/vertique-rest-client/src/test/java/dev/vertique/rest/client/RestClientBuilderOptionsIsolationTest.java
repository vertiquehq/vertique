// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.http.client.VertiqueHttpClient;
import dev.vertique.http.client.VertiqueHttpClientBuilder;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.rest.client.config.RestClientConfig;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code build()} applies per-client config overrides without mutating the caller's options objects. */
class RestClientBuilderOptionsIsolationTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @RestClient(name = "alpha", value = "http://alpha")
    interface AlphaClient {
        @CircuitBreaker(maxFailures = 5, resetTimeoutMs = 4_000)
        @GET
        @Path("/x")
        Future<String> call();
    }

    @RestClient(name = "beta", value = "http://beta")
    interface BetaClient {
        @CircuitBreaker(maxFailures = 5, resetTimeoutMs = 4_000)
        @GET
        @Path("/x")
        Future<String> call();
    }

    private static Map<String, RestClientConfig> configIndex() {
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "alpha",
                                        new JsonObject()
                                                .put("pool", new JsonObject().put("http1MaxSize", 7))
                                                .put(
                                                        "circuitBreaker",
                                                        new JsonObject()
                                                                .put("maxFailures", 2)
                                                                .put("resetTimeoutMs", 5000)))
                                .put(
                                        "beta",
                                        new JsonObject()
                                                .put("pool", new JsonObject().put("http1MaxSize", 9))
                                                .put(
                                                        "circuitBreaker",
                                                        new JsonObject()
                                                                .put("maxFailures", 3)
                                                                .put("resetTimeoutMs", 6000))));
        return RestClientConfig.indexFromConfig(root, new DefaultConfigParser(DefaultConfigMapper.lenient()));
    }

    @Test
    @DisplayName("config overrides never change a pool or circuit-breaker options object shared by the caller")
    void sharedOptionsAreNotMutated() {
        PoolOptions sharedPool = new PoolOptions().setHttp1MaxSize(1).setMaxWaitQueueSize(11);
        CircuitBreakerOptions sharedBreaker = new CircuitBreakerOptions().setMaxFailures(1);
        Map<String, RestClientConfig> index = configIndex();

        List<Integer> http1MaxSizeSeenByFactory = new ArrayList<>();
        List<Integer> waitQueueSeenByFactory = new ArrayList<>();
        RestClientBuilder.HttpTransportFactory factory = v -> {
            VertiqueHttpClientBuilder governed = mock(VertiqueHttpClientBuilder.class, RETURNS_SELF);
            when(governed.poolOptions(any())).thenAnswer(invocation -> {
                PoolOptions pool = invocation.getArgument(0);
                http1MaxSizeSeenByFactory.add(pool.getHttp1MaxSize());
                waitQueueSeenByFactory.add(pool.getMaxWaitQueueSize());
                return governed;
            });
            when(governed.build()).thenReturn(mock(VertiqueHttpClient.class));
            return governed;
        };

        RestClientBuilder alphaBuilder = new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory)
                .poolOptions(sharedPool)
                .circuitBreaker(sharedBreaker)
                .config(index.get("alpha"));
        RestClientBuilder betaBuilder = new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory)
                .poolOptions(sharedPool)
                .circuitBreaker(sharedBreaker)
                .config(index.get("beta"));
        try {
            alphaBuilder.build(AlphaClient.class);
            betaBuilder.build(BetaClient.class);

            assertThat(sharedPool.getHttp1MaxSize()).isEqualTo(1);
            assertThat(sharedPool.getMaxWaitQueueSize()).isEqualTo(11);
            assertThat(sharedBreaker.getMaxFailures()).isEqualTo(1);

            // Each client still gets its own overrides, applied on top of the shared baseline
            // (the unoverridden wait-queue size comes from the shared object, not from the other client).
            assertThat(http1MaxSizeSeenByFactory).containsExactly(7, 9);
            assertThat(waitQueueSeenByFactory).containsExactly(11, 11);
        } finally {
            alphaBuilder.close().toCompletionStage().toCompletableFuture().join();
            betaBuilder.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
