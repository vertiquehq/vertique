// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.rest.core.RestConfigurationException;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the ownership and failure-mode contract of {@link RestTestMounts}: {@link
 * RestTestMounts#router} produces the API router on its own and funnels a synchronous build failure
 * into its documented single failure channel, it never closes a caller-supplied {@link Vertx} on any
 * path, a blocking start surfaces an exhausted budget as a thrown exception rather than a hang, a
 * start that really reaches the await rethrows its runtime cause unwrapped, a call from an
 * event-loop thread is refused rather than deadlocked, and its recursive delete helper matches the
 * private copies it replaces in the consumer integration tests while refusing the degenerate targets
 * those copies would happily have walked.
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

    // --- Router assembly ---

    @Test
    @DisplayName("router builds the API router on its own, without going through startServer")
    void routerReturnsApiRouterWithoutStartingServer() throws Exception {
        // given: a mount whose graph can resolve its validation strategy, and one JAX-RS resource.
        RestTestMount mount = mount(noneStrategyConfig());

        // when: only router(...) is called — no server assembly, no bind.
        Router apiRouter = await(RestTestMounts.router(vertx, mount, resources()));

        // then: the future resolves to a usable router carrying the mounted resource. The route
        // assertion is what keeps this from passing on an empty router the build never populated.
        assertThat(apiRouter).isNotNull();
        assertThat(apiRouter.getRoutes())
                .as("the resource must actually have been registered on the returned router")
                .isNotEmpty();
    }

    @Test
    @DisplayName("router turns a synchronous router-build failure into a failed future instead of throwing")
    void routerNormalisesSynchronousBuildFailureIntoFailedFuture() throws Exception {
        // given: an empty-config graph. JaxRsRouterMount.createRouter resolves the configured
        // validation strategy eagerly, and RequestValidationStrategySelector.select *throws* rather
        // than returning a failed future — so without normalisation the exception escapes the call.
        RestTestMount mount = mount(new JsonObject());
        AtomicReference<Future<Router>> returned = new AtomicReference<>();

        // when: the failure must arrive through the return value, not the stack.
        assertThatCode(() -> returned.set(RestTestMounts.router(vertx, mount, resources())))
                .as("router documents a single failure channel: a synchronous throw must not escape it")
                .doesNotThrowAnyException();

        // then: and it must be the original exception, not a wrapper a caller cannot assert on.
        Throwable cause = awaitFailure(returned.get());
        assertThat(cause)
                .as("the returned future carries the router-build failure unwrapped")
                .isInstanceOf(RestConfigurationException.class);
    }

    // --- Vertx ownership ---

    @Test
    @DisplayName("startServer never closes the caller-supplied Vertx, on the failure path or the success path")
    void doesNotCloseCallerSuppliedVertx() throws Exception {
        // Failure path: an empty-config graph cannot resolve its configured validation strategy, so
        // the router build fails. A helper that owned the Vertx would be tempted to close it here.
        Future<HttpServer> failed = RestTestMounts.startServer(vertx, mount(new JsonObject()), resources());
        Throwable cause = awaitFailure(failed);
        assertThat(cause).isInstanceOf(RestConfigurationException.class);

        // Success path.
        HttpServer server = await(RestTestMounts.startServer(vertx, mount(noneStrategyConfig()), resources()));
        assertThat(server.actualPort()).isPositive();

        // The caller still owns the Vertx after both paths: a fresh server binds on it.
        HttpServer probe = await(vertx.createHttpServer()
                .requestHandler(request -> request.response().end())
                .listen(0, "127.0.0.1"));
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
        RestTestMount mount = mount(noneStrategyConfig());

        assertThatThrownBy(() -> RestTestMounts.startServerBlocking(vertx, mount, resources(), Duration.ofNanos(1)))
                .isInstanceOf(TechnicalException.class)
                .hasMessageContaining("did not start within")
                .hasCauseInstanceOf(TimeoutException.class);

        // The timeout is a caller-visible failure, not a teardown: the Vertx is untouched.
        HttpServer probe = await(vertx.createHttpServer()
                .requestHandler(request -> request.response().end())
                .listen(0, "127.0.0.1"));
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
        RestTestMount mount = mount(new JsonObject());

        // when/then: unwrap must surface the original runtime cause, not a TechnicalException wrapper,
        // so a test can assert on the framework's own exception type.
        assertThatThrownBy(() -> RestTestMounts.startServerBlocking(vertx, mount, resources(), Duration.ofSeconds(5)))
                .isInstanceOf(RestConfigurationException.class)
                .as("the router-build failure is rethrown as-is, never wrapped")
                .isNotInstanceOf(TechnicalException.class);
    }

    // --- Event-loop guard ---

    @Test
    @DisplayName("startServerBlocking refuses to run on a Vert.x event-loop thread instead of deadlocking")
    void startServerBlockingRejectsEventLoopThread() throws Exception {
        RestTestMount mount = mount(noneStrategyConfig());
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();

        vertx.getOrCreateContext().runOnContext(ignored -> {
            try {
                HttpServer unexpected =
                        RestTestMounts.startServerBlocking(vertx, mount, resources(), Duration.ofSeconds(5));
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

    @Test
    @DisplayName("deleteRecursively refuses a blank or empty path instead of deleting the working directory")
    void deleteRecursivelyRejectsAPathResolvingToTheWorkingDirectory() {
        // The accident this guards: an unset configured uploads directory arrives as "", which
        // Path.of resolves to the current working directory — under Maven, the module source tree.
        assertThatThrownBy(() -> RestTestMounts.deleteRecursively(java.nio.file.Path.of("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> RestTestMounts.deleteRecursively(java.nio.file.Path.of("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        // Spelling the same location a different way must not slip past the blank check.
        assertThatThrownBy(() -> RestTestMounts.deleteRecursively(java.nio.file.Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current working directory");

        assertThat(Files.isDirectory(java.nio.file.Path.of("").toAbsolutePath().normalize()))
                .as("the working directory must be untouched by the rejected calls")
                .isTrue();
    }

    @Test
    @DisplayName("deleteRecursively refuses a filesystem root")
    void deleteRecursivelyRejectsAFilesystemRoot(@TempDir java.nio.file.Path tempDir) {
        java.nio.file.Path root = tempDir.toAbsolutePath().getRoot();

        assertThatThrownBy(() -> RestTestMounts.deleteRecursively(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filesystem root");

        assertThat(Files.isDirectory(tempDir))
                .as("nothing below the root may have been walked")
                .isTrue();
    }

    // --- Helpers ---

    /**
     * Builds a real mount handle over the fixture graph.
     *
     * @param config the application configuration the graph is built from
     * @return the mount handle
     */
    private static RestTestMount mount(JsonObject config) {
        return DaggerFixtureSelfTestComponent.factory()
                .create(vertx, config, RestTestContributions.none())
                .testMount();
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
