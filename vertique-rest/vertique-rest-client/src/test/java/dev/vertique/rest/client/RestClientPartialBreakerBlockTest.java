// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.http.client.HttpClient;
import dev.vertique.http.client.HttpClientBuilder;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.rest.client.config.RestClientConfig;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@code restClient.<name>.circuitBreaker} block that sets only some fields keeps the builder or
 * default value for each field it leaves unset, so building the client succeeds.
 */
class RestClientPartialBreakerBlockTest {

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

    @RestClient(name = "plain", value = "http://plain")
    interface PlainClient {
        @GET
        @Path("/x")
        Future<String> call();
    }

    private static RestClientConfig blockWith(String key, int value) {
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "plain",
                                        new JsonObject().put("circuitBreaker", new JsonObject().put(key, value))));
        Map<String, RestClientConfig> index =
                RestClientConfig.indexFromConfig(root, new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return index.get("plain");
    }

    private static RestClientBuilder.HttpTransportFactory factory() {
        return v -> {
            HttpClientBuilder governed = mock(HttpClientBuilder.class, RETURNS_SELF);
            when(governed.build()).thenReturn(mock(HttpClient.class));
            return governed;
        };
    }

    private static void assertBuilds(RestClientBuilder builder) {
        try {
            assertThatCode(() -> builder.build(PlainClient.class)).doesNotThrowAnyException();
        } finally {
            builder.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("a block that sets only maxFailures builds on top of a builder-level breaker")
    void maxFailuresOnlyWithBuilderBreaker() {
        assertBuilds(new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory())
                .circuitBreaker(new CircuitBreakerOptions().setMaxFailures(9).setResetTimeout(4_000))
                .config(blockWith("maxFailures", 2)));
    }

    @Test
    @DisplayName("a block that sets only resetTimeoutMs builds on top of a builder-level breaker")
    void resetTimeoutOnlyWithBuilderBreaker() {
        assertBuilds(new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory())
                .circuitBreaker(new CircuitBreakerOptions().setMaxFailures(9).setResetTimeout(4_000))
                .config(blockWith("resetTimeoutMs", 5_000)));
    }

    @Test
    @DisplayName("a block that sets both fields builds on top of a builder-level breaker")
    void bothFieldsWithBuilderBreaker() {
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put(
                                        "plain",
                                        new JsonObject()
                                                .put(
                                                        "circuitBreaker",
                                                        new JsonObject()
                                                                .put("maxFailures", 2)
                                                                .put("resetTimeoutMs", 5_000))));
        RestClientConfig config = RestClientConfig.indexFromConfig(
                        root, new DefaultConfigParser(DefaultConfigMapper.lenient()))
                .get("plain");
        assertBuilds(new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory())
                .circuitBreaker(new CircuitBreakerOptions().setMaxFailures(9).setResetTimeout(4_000))
                .config(config));
    }

    @Test
    @DisplayName("a block that sets only maxFailures builds with no builder-level breaker")
    void maxFailuresOnlyWithoutBuilderBreaker() {
        assertBuilds(new RestClientBuilder(vertx, ResiliencePolicyRegistry.empty(), factory())
                .config(blockWith("maxFailures", 2)));
    }
}
