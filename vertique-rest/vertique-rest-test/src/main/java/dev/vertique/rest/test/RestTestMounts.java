// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Mount and server helpers for tests that drive a fixture-built {@link JaxRsRouterMount.Factory}.
 *
 * <p>Pure Vert.x: no Dagger and no JUnit, so it composes with any test framework and with a factory
 * from any source. {@link RestTestFixtureModule} produces the factory; this class turns it into a
 * {@link Router} or a listening {@link HttpServer}, absorbing the mount/subrouter/bind boilerplate
 * that REST integration tests otherwise copy verbatim.
 *
 * <h2>Ownership</h2>
 *
 * <p>These helpers <b>never close a caller-supplied {@link Vertx}</b>, on any path — success,
 * router-build failure, or start timeout. The caller created it and the caller closes it. The
 * {@link HttpServer} they return is likewise the caller's to close; the only server this class ever
 * closes is one that binds after {@link #startServerBlocking} has already given up on it, which
 * would otherwise leak a bound port.
 */
public final class RestTestMounts {

    /**
     * The mount path every consumer integration test uses. It has zero variance across the harnesses
     * this class replaces, so it is fixed rather than exposed as a parameter.
     */
    private static final String MOUNT_PATH = "/*";

    /**
     * The OpenAPI document path passed to the factory. The JAX-RS router is built entirely from the
     * annotation model, so this value is documentation metadata only and likewise never varies.
     */
    private static final String OPENAPI_PATH = "openapi.json";

    /** Not instantiable. */
    private RestTestMounts() {}

    // --- Router assembly ---

    /**
     * Creates the API {@link Router} for the given resources from a real mount factory.
     *
     * <p>Router construction is partly synchronous — the configured request-validation strategy is
     * resolved eagerly, and an unresolvable id throws. Those synchronous failures are converted to a
     * failed future so callers have a single failure channel.
     *
     * @param vertx     the Vert.x instance; never closed by this method
     * @param factory   the mount factory, typically obtained from a component over
     *                  {@link RestTestFixtureModule}
     * @param resources the JAX-RS resource instances to mount
     * @return a future resolving to the configured API router, or a failed future carrying the
     *         router-build failure
     */
    public static Future<Router> router(Vertx vertx, JaxRsRouterMount.Factory factory, Set<Object> resources) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(resources, "resources");
        try {
            return factory.create(MOUNT_PATH, OPENAPI_PATH, resources).createRouter(vertx);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    // --- Server start ---

    /**
     * Builds the API router and starts an HTTP server serving it on an ephemeral port.
     *
     * <p>The API router is mounted as a sub-router under {@code /*} of a fresh root router, matching
     * how the framework's own HTTP verticle assembles a mount. The server binds to port {@code 0};
     * read the assigned port from {@link HttpServer#actualPort()} <em>after</em> this future
     * resolves — never by probing for a free port beforehand.
     *
     * @param vertx     the Vert.x instance; never closed by this method
     * @param factory   the mount factory
     * @param resources the JAX-RS resource instances to mount
     * @return a future resolving to the listening server, which the caller owns and must close
     */
    public static Future<HttpServer> startServer(Vertx vertx, JaxRsRouterMount.Factory factory, Set<Object> resources) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(resources, "resources");
        return router(vertx, factory, resources).compose(apiRouter -> {
            Router root = Router.router(vertx);
            root.route(MOUNT_PATH).subRouter(apiRouter);
            return vertx.createHttpServer().requestHandler(root).listen(0);
        });
    }

    /**
     * Blocking form of {@link #startServer}, for tests whose methods are synchronous.
     *
     * <p>{@code timeout} bounds the <em>whole</em> start — the synchronous router build as well as
     * the bind — so the call returns or throws within roughly that budget. It never hangs: an
     * exhausted budget surfaces as a {@link TechnicalException} whose cause is a
     * {@link TimeoutException}. If the server binds after that, it is closed rather than leaked.
     *
     * <p>Must not be called from a Vert.x event-loop thread, which this would deadlock.
     *
     * @param vertx     the Vert.x instance; never closed by this method, including on timeout
     * @param factory   the mount factory
     * @param resources the JAX-RS resource instances to mount
     * @param timeout   the total budget for building the router and binding the server
     * @return the listening server, which the caller owns and must close
     * @throws TechnicalException if the budget is exhausted, the calling thread is interrupted, or
     *                            the start fails with a non-runtime cause
     */
    public static HttpServer startServerBlocking(
            Vertx vertx, JaxRsRouterMount.Factory factory, Set<Object> resources, Duration timeout) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(timeout, "timeout");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Future<HttpServer> starting = startServer(vertx, factory, resources);
        long remainingNanos = deadlineNanos - System.nanoTime();
        try {
            if (remainingNanos <= 0) {
                throw new TimeoutException("the start budget was exhausted before the server bound");
            }
            return starting.toCompletionStage().toCompletableFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            closeWhenBound(starting);
            throw new TechnicalException("HTTP server did not start within " + timeout, e);
        } catch (ExecutionException e) {
            closeWhenBound(starting);
            throw unwrap(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeWhenBound(starting);
            throw new TechnicalException("Interrupted while starting the HTTP server", e);
        }
    }

    // --- Filesystem cleanup ---

    /**
     * Recursively deletes {@code directory} and everything below it, depth-first.
     *
     * <p>A directory that does not exist is a no-op, so this is safe to call unconditionally from a
     * teardown method whose test may have failed before creating anything. This replaces the
     * byte-identical private helpers the upload-oriented REST integration tests each carried.
     *
     * @param directory the directory to remove; must not be {@code null}, but need not exist
     * @throws UncheckedIOException if the tree cannot be walked or a path cannot be deleted
     */
    public static void deleteRecursively(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (Files.notExists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + directory, e);
        }
    }

    // --- Internals ---

    /**
     * Closes the server once it binds, for a start this class has already abandoned. Without this a
     * timed-out or interrupted start would leave a listening socket behind for the rest of the JVM's
     * life.
     *
     * @param starting the in-flight start future
     */
    private static void closeWhenBound(Future<HttpServer> starting) {
        starting.onSuccess(server -> server.close());
    }

    /**
     * Unwraps the cause of a failed start into an unchecked exception, preserving a runtime cause
     * (such as a router-build failure) so callers can assert on the framework's own exception types.
     *
     * @param e the execution exception raised while awaiting the start
     * @return the exception to throw
     */
    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new TechnicalException("Failed to start the HTTP server", cause != null ? cause : e);
    }
}
