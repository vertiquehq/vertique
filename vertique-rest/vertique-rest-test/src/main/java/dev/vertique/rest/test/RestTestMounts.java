// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
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
 * Mount and server helpers for tests that drive a fixture-built {@link RestTestMount}.
 *
 * <p>Pure Vert.x: no Dagger and no JUnit, so it composes with any test framework.
 * {@link RestTestFixtureModule} produces the {@link RestTestMount}; this class turns it into a
 * {@link Router} or a listening {@link HttpServer}, absorbing the mount/subrouter/bind boilerplate
 * that REST integration tests otherwise copy verbatim.
 *
 * <p>These helpers take the opaque {@link RestTestMount} rather than a bare
 * {@link JaxRsRouterMount.Factory}, and there is deliberately no factory-only form: a mount needs
 * both middleware tiers to behave like production, so the handle leaves nothing to reach for that
 * would drop one of them by accident. That is ergonomics, not enforcement — {@link #router} still
 * produces the API router alone on purpose, for a caller assembling its own root router. See
 * {@link RestTestMount} for the full rationale.
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
     * <p>The returned router is the <em>API</em> router only. It carries the API-scoped middlewares
     * {@code JaxRsRouterMount} installs on it, but not the ROOT-scoped ones — those belong on a root
     * router above it, which {@link #startServer} builds. A caller that mounts this router itself is
     * responsible for that tier.
     *
     * @param vertx     the Vert.x instance; never closed by this method
     * @param mount     the mount handle, obtained from a component over
     *                  {@link RestTestFixtureModule}
     * @param resources the JAX-RS resource instances to mount
     * @return a future resolving to the configured API router, or a failed future carrying the
     *         router-build failure
     */
    public static Future<Router> router(Vertx vertx, RestTestMount mount, Set<Object> resources) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(mount, "mount");
        Objects.requireNonNull(resources, "resources");
        try {
            return mount.factory().create(MOUNT_PATH, OPENAPI_PATH, resources).createRouter(vertx);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    // --- Server start ---

    /**
     * Builds the API router and starts an HTTP server serving it on an ephemeral port.
     *
     * <p>The server is assembled the way {@code HttpVerticle} assembles a single mount: a fresh root
     * router carrying the graph's ROOT-scoped middlewares, with the API router mounted below it as a
     * sub-router under {@code /*}. Both middleware tiers therefore run — the ROOT one installed here,
     * the API one installed by {@code JaxRsRouterMount} — so a request traverses the same pipeline it
     * would in a deployed application.
     *
     * <p><b>The boundary of that fidelity.</b> This assembles <em>one</em> JAX-RS mount with its
     * production middleware pipelines. It is not a substitute for {@code HttpVerticle}: mount sorting
     * and overlap detection, {@code MountCustomizer} and {@code RouterCustomizer} hooks, and the
     * configured {@code HttpServerOptions} (TLS, compression, timeouts) are all outside it. A test
     * asserting on any of those needs a real verticle.
     *
     * <p>The server binds to port {@code 0}; read the assigned port from
     * {@link HttpServer#actualPort()} <em>after</em> this future resolves — never by probing for a
     * free port beforehand.
     *
     * <p>The bind address is fixed to {@value #LOOPBACK_HOST}, so the mount is reachable only from
     * the machine running the test. Connect to it as {@code 127.0.0.1} or {@code localhost}.
     *
     * @param vertx     the Vert.x instance; never closed by this method
     * @param mount     the mount handle
     * @param resources the JAX-RS resource instances to mount
     * @return a future resolving to the listening server, which the caller owns and must close
     */
    public static Future<HttpServer> startServer(Vertx vertx, RestTestMount mount, Set<Object> resources) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(mount, "mount");
        Objects.requireNonNull(resources, "resources");
        // Root router first, then the API router — the order HttpVerticle uses (create main router,
        // install ROOT middleware, then create and mount each RouterMount's router). Nothing about a
        // successful request observes the difference, but a failure in the root install now surfaces
        // before createRouter has run the RouterLifecycleHook.afterRouterCreated hooks, whose side
        // effects this fixture has no path to undo. The try/catch keeps the single failure channel
        // router(...) documents: a synchronous throw here would otherwise escape past the Future.
        Router root;
        try {
            root = Router.router(vertx);
            installRootMiddlewares(root, mount.middlewares());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return router(vertx, mount, resources).compose(apiRouter -> {
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
     * @param mount     the mount handle
     * @param resources the JAX-RS resource instances to mount
     * @param timeout   the budget for the start, charged from before the router build and bounding
     *                  the wait for the bind
     * @return the listening server, which the caller owns and must close
     * @throws IllegalStateException if called from a Vert.x event-loop thread
     * @throws TechnicalException if the budget is exhausted, the calling thread is interrupted, or
     *                            the start fails with a non-runtime cause
     */
    public static HttpServer startServerBlocking(
            Vertx vertx, RestTestMount mount, Set<Object> resources, Duration timeout) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(mount, "mount");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(timeout, "timeout");
        requireNotOnEventLoop();

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Future<HttpServer> starting = startServer(vertx, mount, resources);
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
     * <p><b>Degenerate targets are rejected rather than deleted.</b> The realistic accident is not a
     * malicious path but an unset one: a test that reads its uploads directory from configuration and
     * gets back {@code ""} hands over {@link Path#of(String, String...) Path.of("")}, which resolves
     * to the <em>current working directory</em> — for a Maven build, the module source tree. This
     * method therefore refuses a path that resolves to the working directory and one that resolves to
     * a filesystem root, before it walks anything. Both are checked against the
     * {@linkplain Path#toAbsolutePath() absolute}, {@linkplain Path#normalize() normalized} form, so
     * {@code ""}, {@code "."}, and {@code "uploads/.."} are all caught.
     *
     * <p>Symbolic links need no special handling: {@link Files#walk} does not follow them unless
     * {@code FOLLOW_LINKS} is passed, so a link inside the tree is deleted as a link and its target is
     * left alone.
     *
     * @param directory the directory to remove; must not be {@code null}, but need not exist
     * @throws IllegalArgumentException if {@code directory} is blank, resolves to the current working
     *                                  directory, or resolves to a filesystem root
     * @throws UncheckedIOException if the tree cannot be walked or a path cannot be deleted
     */
    public static void deleteRecursively(Path directory) {
        Objects.requireNonNull(directory, "directory");
        requireDeletableTarget(directory);
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
     * Rejects the two paths that {@link #deleteRecursively} must never walk: the current working
     * directory and a filesystem root.
     *
     * <p>The blank check runs on the path as written, because an entirely blank string is the shape a
     * missing configuration value arrives in and naming it in the failure message is far more useful
     * than reporting the working directory it silently became. The remaining checks run on the
     * absolute, normalized form so a relative path cannot slip past by spelling the same location
     * differently ({@code "."}, {@code "uploads/.."}).
     *
     * <p>A filesystem root is identified by having no parent once absolute and normalized — true of
     * {@code /} and of {@code C:\}, and false of every real upload directory.
     *
     * @param directory the caller-supplied path
     * @throws IllegalArgumentException if the path is blank, is the working directory, or is a root
     */
    private static void requireDeletableTarget(Path directory) {
        if (directory.toString().isBlank()) {
            throw new IllegalArgumentException("directory must not be blank: a blank or missing configured path "
                    + "resolves to the current working directory, which for a build is the module source tree");
        }
        Path resolved = directory.toAbsolutePath().normalize();
        if (resolved.equals(Path.of("").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "refusing to recursively delete the current working directory: " + resolved);
        }
        if (resolved.getParent() == null) {
            throw new IllegalArgumentException("refusing to recursively delete a filesystem root: " + resolved);
        }
    }

    /**
     * Mounts the ROOT-scoped middlewares on the root router, in
     * {@link OrderedExtension#comparator()} order (phase → priority → orderKey).
     *
     * <p>This mirrors {@code HttpVerticle}'s own installation step, filter for filter, and is the
     * only place this class orders anything. Ordering is not policy invented here: middlewares are
     * bound as a {@code Set}, there is no {@code sortedMiddlewares} provider to delegate to the way
     * encoders and decoders delegate to {@code RestModule}, and <em>both</em> production
     * installation sites ({@code HttpVerticle} for ROOT, {@code JaxRsRouterMount} for API) sort
     * inline with the same comparator. Reproducing that is what makes the pipeline faithful.
     *
     * @param root        the root router to install on
     * @param middlewares the graph's complete middleware set, of both scopes
     */
    private static void installRootMiddlewares(Router root, Set<Middleware> middlewares) {
        middlewares.stream()
                .filter(m -> m.scope() == MiddlewareScope.ROOT)
                .sorted(OrderedExtension.comparator())
                .forEach(m -> root.route(m.path()).handler(m));
    }

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
