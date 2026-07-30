// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the ownership and failure-mode contract of {@link RestTestMounts}: it never closes a
 * caller-supplied {@link Vertx} on any path, a blocking start surfaces an exhausted budget as a
 * thrown exception rather than a hang, a start that really reaches the await rethrows its runtime
 * cause unwrapped, a call from an event-loop thread is refused rather than deadlocked, and its
 * recursive delete helper matches the private copies it replaces in the consumer integration tests.
 *
 * <p>The two blocking-start failure tests cover different branches on purpose:
 * {@code startServerBlockingTimesOutCleanly} pins the pre-exhausted budget branch that throws before
 * awaiting at all, while {@code startServerBlockingRethrowsRouterBuildFailureUnwrapped} is the only
 * one that reaches the real timed {@code get}.
 *
 * <p>Wire-level behaviour of the mounted router lives in {@code RestTestMountsIT}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestTestMountsTest {

    /** Bound for every awaited Vert.x operation in this class. */
    private static final long AWAIT_SECONDS = 5;

    private static Vertx vertx;

    @BeforeAll
    static void createVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    // --- Vertx ownership ---

    @Test
    @DisplayName("startServer never closes the caller-supplied Vertx, on the failure path or the success path")
    void doesNotCloseCallerSuppliedVertx() throws Exception {
        // Failure path: an empty-config graph cannot resolve its configured validation strategy, so
        // the router build fails. A helper that owned the Vertx would be tempted to close it here.
        Future<HttpServer> failed = RestTestMounts.startServer(vertx, factory(new JsonObject()), resources());
        Throwable cause = awaitFailure(failed);
        assertThat(cause).isInstanceOf(RestConfigurationException.class);

        // Success path.
        HttpServer server = await(RestTestMounts.startServer(vertx, factory(noneStrategyConfig()), resources()));
        assertThat(server.actualPort()).isPositive();

        // The caller still owns the Vertx after both paths: a fresh server binds on it.
        HttpServer probe = await(vertx.createHttpServer()
                .requestHandler(request -> request.response().end())
                .listen(0));
        assertThat(probe.actualPort())
                .as("the caller-supplied Vertx must still be usable after startServer")
                .isPositive();

        await(probe.close());
        await(server.close());
    }

    // --- Blocking start ---

    @Test
    @DisplayName("startServerBlocking surfaces an exhausted timeout as an exception instead of hanging")
    void startServerBlockingTimesOutCleanly() throws Exception {
        JaxRsRouterMount.Factory factory = factory(noneStrategyConfig());

        assertThatThrownBy(() -> RestTestMounts.startServerBlocking(vertx, factory, resources(), Duration.ofNanos(1)))
                .isInstanceOf(TechnicalException.class)
                .hasMessageContaining("did not start within")
                .hasCauseInstanceOf(TimeoutException.class);

        // The timeout is a caller-visible failure, not a teardown: the Vertx is untouched.
        HttpServer probe = await(vertx.createHttpServer()
                .requestHandler(request -> request.response().end())
                .listen(0));
        assertThat(probe.actualPort()).isPositive();
        await(probe.close());
    }

    @Test
    @DisplayName("startServerBlocking rethrows a router-build failure unwrapped after really awaiting the start")
    void startServerBlockingRethrowsRouterBuildFailureUnwrapped() {
        // given: an empty-config graph, whose router build fails because the configured validation
        // strategy id cannot be resolved, and a budget generous enough that the pre-exhausted early
        // throw cannot fire — so the real get(remaining, NANOSECONDS) await is reached and completes
        // with an ExecutionException.
        JaxRsRouterMount.Factory factory = factory(new JsonObject());

        // when/then: unwrap must surface the original runtime cause, not a TechnicalException wrapper,
        // so a test can assert on the framework's own exception type.
        assertThatThrownBy(() -> RestTestMounts.startServerBlocking(vertx, factory, resources(), Duration.ofSeconds(5)))
                .isInstanceOf(RestConfigurationException.class)
                .as("the router-build failure is rethrown as-is, never wrapped")
                .isNotInstanceOf(TechnicalException.class);
    }

    // --- Event-loop guard ---

    @Test
    @DisplayName("startServerBlocking refuses to run on a Vert.x event-loop thread instead of deadlocking")
    void startServerBlockingRejectsEventLoopThread() throws Exception {
        JaxRsRouterMount.Factory factory = factory(noneStrategyConfig());
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            try {
                HttpServer unexpected =
                        RestTestMounts.startServerBlocking(vertx, factory, resources(), Duration.ofSeconds(5));
                unexpected.close();
                thrown.complete(null);
            } catch (Throwable t) {
                thrown.complete(t);
            }
        });

        assertThat(thrown.get(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("calling from an event-loop thread must fail fast, not time out")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be called from a Vert.x event-loop thread");
    }

    // --- Recursive delete ---

    @Test
    @DisplayName("deleteRecursively removes a nested directory tree")
    void deleteRecursivelyRemovesNestedTree(@TempDir java.nio.file.Path tempDir) throws IOException {
        java.nio.file.Path root = tempDir.resolve("uploads");
        java.nio.file.Path nested = root.resolve("a/b");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("upload.bin"), "payload");
        Files.writeString(root.resolve("top.bin"), "payload");

        RestTestMounts.deleteRecursively(root);

        assertThat(Files.exists(root)).isFalse();
        assertThat(Files.exists(tempDir))
                .as("only the requested subtree is removed")
                .isTrue();
    }

    @Test
    @DisplayName("deleteRecursively is a no-op for a directory that does not exist")
    void deleteRecursivelyIgnoresMissingDirectory(@TempDir java.nio.file.Path tempDir) {
        java.nio.file.Path missing = tempDir.resolve("never-created");

        assertThatCode(() -> RestTestMounts.deleteRecursively(missing)).doesNotThrowAnyException();
    }

    // --- Helpers ---

    /**
     * Builds a real mount factory over the fixture graph.
     *
     * @param config the application configuration the graph is built from
     * @return the mount factory
     */
    private static JaxRsRouterMount.Factory factory(JsonObject config) {
        return DaggerFixtureSelfTestComponent.factory()
                .create(vertx, config, RestTestContributions.none())
                .mountFactory();
    }

    /**
     * Returns the single-resource set every test in this class mounts.
     *
     * @return the JAX-RS resource instances
     */
    private static Set<Object> resources() {
        return Set.of(new PingResource());
    }

    /**
     * Returns a fresh configuration selecting the {@code none} validation strategy.
     *
     * @return the configuration object
     */
    private static JsonObject noneStrategyConfig() {
        return new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    /**
     * Awaits a future that is expected to succeed.
     *
     * @param <T>    the future's result type
     * @param future the future to await
     * @return the future's result
     * @throws Exception when the future fails or does not settle in time
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Awaits a future that is expected to fail, without letting a success hang the test.
     *
     * @param future the future to await
     * @return the failure cause, or {@code null} if the future unexpectedly succeeded
     * @throws Exception when the future does not settle in time
     */
    private static Throwable awaitFailure(Future<?> future) throws Exception {
        CompletableFuture<Throwable> settled = new CompletableFuture<>();
        future.onSuccess(value -> settled.complete(null)).onFailure(settled::complete);
        return settled.get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** JAX-RS resource used only to give the mount something to register. */
    @Path("/mounts")
    public static class PingResource {

        /**
         * Returns a constant body.
         *
         * @return the literal {@code "pong"}
         */
        @GET
        @Path("/ping")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountsPing")
        public String ping() {
            return "pong";
        }
    }
}
