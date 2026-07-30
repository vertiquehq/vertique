// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import io.vertx.core.Context;
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
 * closes is one that binds after {@link #startServerBlocking} has already given up on it — a
 * best-effort close of a port that would otherwise leak.
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

    /**
     * The bind address of every server this class starts. The fixture mount is unauthenticated and
     * may expose endpoints that write request bodies to disk, so it must never be reachable from
     * outside the machine running the test — Vert.x would otherwise default to {@code 0.0.0.0}.
     *
     * <p>The literal address is used rather than the name {@code localhost}: a name is resolved
     * through the platform's host database and can, in principle, map to a routable address, which
     * would silently defeat the restriction. This matches the {@code listen(0, "127.0.0.1")} idiom
     * the framework's own HTTP integration tests already use.
     */
    private static final String LOOPBACK_HOST = "127.0.0.1";

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
     * <p>The bind address is fixed to {@value #LOOPBACK_HOST}, so the mount is reachable only from
     * the machine running the test. Connect to it as {@code 127.0.0.1} or {@code localhost}.
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
            return vertx.createHttpServer().requestHandler(root).listen(0, LOOPBACK_HOST);
        });
    }

    /**
     * Blocking form of {@link #startServer}, for tests whose methods are synchronous.
     *
     * <p>{@code timeout} is a deadline captured before the start begins, so the router build is
     * <em>charged</em> against it: whatever the build consumes is subtracted from the budget left for
     * the bind. It does not <em>bound</em> the build — {@link #router} runs
     * {@code createRouter(vertx)} synchronously on the calling thread, and a build that blocks
     * indefinitely blocks this call indefinitely. What the budget bounds is the await: once the build
     * returns, an unbound server surfaces as a {@link TechnicalException} whose cause is a
     * {@link TimeoutException} rather than a hang. If the server binds after that, a close is issued
     * on a best-effort basis (see {@link #closeWhenBound}) rather than leaking the port.
     *
     * <p>Must not be called from a Vert.x event-loop thread — that would block the loop the start
     * needs in order to complete. This is enforced: such a call fails fast with an
     * {@link IllegalStateException} instead of deadlocking until the budget expires and then
     * reporting a misleading timeout.
     *
     * @param vertx     the Vert.x instance; never closed by this method, including on timeout
     * @param factory   the mount factory
     * @param resources the JAX-RS resource instances to mount
     * @param timeout   the budget for the start, charged from before the router build and bounding
     *                  the wait for the bind
     * @return the listening server, which the caller owns and must close
     * @throws IllegalStateException if called from a Vert.x event-loop thread
     * @throws TechnicalException if the budget is exhausted, the calling thread is interrupted, or
     *                            the start fails with a non-runtime cause
     */
    public static HttpServer startServerBlocking(
            Vertx vertx, JaxRsRouterMount.Factory factory, Set<Object> resources, Duration timeout) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(timeout, "timeout");
        requireNotOnEventLoop();

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
     * Rejects a call made from a Vert.x event-loop thread, which would otherwise block the very loop
     * the start has to run on: the await could then only end by exhausting the budget, reporting a
     * {@link TimeoutException} for what is really a threading mistake.
     *
     * <p>The current context is read with {@link Vertx#currentContext()}, <b>not</b>
     * {@code vertx.getOrCreateContext()}: the latter <em>creates</em> an event-loop context when the
     * caller is not on a Vert.x thread at all, so {@code isEventLoopContext()} would be {@code true}
     * for an ordinary test thread and this guard would reject every legitimate call.
     * {@link Vertx#currentContext()} returns {@code null} off a Vert.x thread, which is the
     * distinction the guard needs. A worker context is deliberately allowed — blocking is what worker
     * threads are for.
     *
     * @throws IllegalStateException if the calling thread is a Vert.x event-loop thread
     */
    private static void requireNotOnEventLoop() {
        Context current = Vertx.currentContext();
        if (current != null && current.isEventLoopContext()) {
            throw new IllegalStateException(
                    "RestTestMounts.startServerBlocking must not be called from a Vert.x event-loop thread; "
                            + "use startServer and compose on the returned Future instead");
        }
    }

    /**
     * Issues a close on the server once it binds, for a start this class has already abandoned.
     * Without this a timed-out or interrupted start would leave a listening socket behind for the
     * rest of the JVM's life.
     *
     * <p><b>Best-effort, not a guarantee.</b> The close is only <em>issued</em>: the returned future
     * is not awaited, so {@link #startServerBlocking} throws before the close settles, and a close
     * that fails is neither retried nor surfaced anywhere. It also does nothing at all for a start
     * that never binds. Treat it as reducing the window for a leaked port, not as closing it.
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
