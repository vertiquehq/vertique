// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.NotFoundException;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that {@link RestTestMounts} turns a fixture-built {@code JaxRsRouterMount.Factory}
 * into a server that behaves like production over real HTTP.
 *
 * <p>These are the tests that justify the whole fixture. {@link #mapsExceptionThroughRealDefaultMapper()}
 * in particular proves that a harness outside {@code dev.vertique.rest.jaxrs} gets the framework's
 * <em>real</em> {@code DefaultExceptionMapper} — the entire capability that widening
 * {@code RestModule.defaultExceptionMapper()} to {@code public} bought. With that capability supplied
 * by this module instead, restoring the method to package-private becomes possible.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestTestMountsIT {

    /** Bound for every awaited request/response round trip. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Generous bound for the blocking server start — this test is not probing the timeout path. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);

    private static Vertx vertx;
    private static HttpClient client;

    private HttpServer server;

    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        Future<?> close = client != null ? client.close() : Future.succeededFuture();
        close.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @AfterEach
    void closeServer() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server = null;
        }
    }

    // --- Production fidelity over the wire ---

    @Test
    @DisplayName("a mounted resource is served end-to-end and encoded by the production StringBodyEncoder")
    void servesRequestThroughRealMount() throws Exception {
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.contentType()).contains("text/plain");
        assertThat(result.body())
                .as("the production StringBodyEncoder writes the raw string; the JSON fallback would quote it")
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("an exception from a mounted resource is mapped by RestModule's real DefaultExceptionMapper")
    void mapsExceptionThroughRealDefaultMapper() throws Exception {
        startServer(RestTestContributions.none());

        HttpResult result = get("/fixture/missing");

        assertThat(result.statusCode())
                .as("dev.vertique.core.exception.NotFoundException maps to 404 only in RestModule's real mapper")
                .isEqualTo(404);
        assertThat(result.contentType()).contains("application/problem+json");
        assertThat(result.body()).contains("no such widget");
    }

    @Test
    @DisplayName("the response serializer selects a contributed encoder over the production default")
    void serializerSelectsContributedEncoderOverProductionDefault() throws Exception {
        startServer(RestTestContributions.builder()
                .addResponseBodyEncoder(new ShoutingStringEncoder())
                .build());

        HttpResult result = get("/fixture/echo");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body())
                .as("the serializer must iterate the same sorted encoder list the mount factory received")
                .isEqualTo("HELLO");
        assertThat(result.contentType()).contains("text/plain");
    }

    // --- Helpers ---

    /**
     * Starts a server over the fixture graph and records it for teardown.
     *
     * @param contributions the additive contributions the graph is built with
     */
    private void startServer(RestTestContributions contributions) {
        FixtureSelfTestComponent component =
                DaggerFixtureSelfTestComponent.factory().create(vertx, noneStrategyConfig(), contributions);
        server = RestTestMounts.startServerBlocking(
                vertx, component.mountFactory(), Set.of(new FixtureResource()), START_TIMEOUT);
    }

    /**
     * Issues a GET against the running server and awaits the full response.
     *
     * @param path the request path
     * @return the status, content type, and body of the response
     * @throws Exception when the round trip fails or times out
     */
    private HttpResult get(String path) throws Exception {
        return client.request(HttpMethod.GET, server.actualPort(), "localhost", path)
                .compose(HttpClientRequest::send)
                .compose(response -> {
                    int statusCode = response.statusCode();
                    String contentType = response.getHeader("Content-Type");
                    return response.body().map(body -> new HttpResult(statusCode, contentType, body.toString()));
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns a fresh configuration selecting the {@code none} validation strategy, which a graph
     * carrying no validation module must set explicitly.
     *
     * @return the configuration object
     */
    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    /**
     * The parts of an HTTP response these tests assert on.
     *
     * @param statusCode  the response status code
     * @param contentType the {@code Content-Type} header, or {@code null} when unset
     * @param body        the response body decoded as a string
     */
    private record HttpResult(int statusCode, String contentType, String body) {}

    /** JAX-RS resource exposing one success path and one exception path. */
    @Path("/fixture")
    public static class FixtureResource {

        /**
         * Returns a constant body encoded by whichever {@code String} encoder ranks first.
         *
         * @return the literal {@code "hello"}
         */
        @GET
        @Path("/echo")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountsEcho")
        public String echo() {
            return "hello";
        }

        /**
         * Always fails with the framework's semantic not-found root.
         *
         * @return never returns
         */
        @GET
        @Path("/missing")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountsMissing")
        public String missing() {
            throw new NotFoundException("no such widget");
        }
    }

    /**
     * Contributed {@code String} encoder at priority {@code 900}, which out-ranks the framework
     * default at {@code 1000}. Its output is deliberately distinguishable on the wire.
     */
    private static final class ShoutingStringEncoder implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == String.class;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            String shouted = String.valueOf(entity).toUpperCase(Locale.ROOT);
            return new BufferedBody(Buffer.buffer(shouted), "text/plain", null);
        }

        @Override
        public int priority() {
            return 900;
        }
    }
}
