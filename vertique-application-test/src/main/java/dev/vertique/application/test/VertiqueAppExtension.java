// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that boots a Vertique application once for a test class and tears it down when
 * the class finishes — so an integration test never hand-writes the asynchronous bootstrap dance or
 * the easy-to-forget awaited teardown.
 *
 * <p>Register it as a {@code @RegisterExtension static final} field so its {@code beforeAll} runs
 * before the test class's own {@code @BeforeAll}, making accessors such as {@link #httpPort()}
 * available for RestAssured wiring:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(MY_FACTORY)
 *         .withConfig(new JsonObject().put("http", new JsonObject().put("port", 0)));
 *
 * @BeforeAll
 * static void setUp() {
 *     RestAssured.port = app.httpPort();
 * }
 * }</pre>
 *
 * <p><strong>Lifecycle.</strong> In {@code beforeAll}, if no {@link Vertx} was supplied the extension
 * creates one and <em>owns</em> it (closing it on teardown); it then awaits {@link
 * VertiqueApplicationBootstrap#start(VertiqueRuntime, VertiqueComponentFactory)} up to the configured
 * {@linkplain #startTimeout(Duration) start timeout}. In {@code afterAll} it awaits {@link
 * VertiqueApplicationHandle#shutdown()} first, then closes the {@code Vertx} instance if it owns it.
 * Blocking the JUnit thread is correct here: that thread is not a Vert.x event-loop thread, so the
 * awaited futures cannot deadlock.
 *
 * <p>The configuration setters ({@link #withConfig}, {@link #withVertx}, {@link #startTimeout}) and
 * {@link #forFactory} return {@code this} for fluent chaining and must be called before the class's
 * tests run (i.e. while building the {@code @RegisterExtension} field). The started-state accessors
 * ({@link #vertx()}, {@link #handle()}, {@link #component()}, {@link #httpPort()}) are valid only
 * after {@code beforeAll}; {@link #config()} is valid at any time.
 *
 * <p>The class is {@code final} and its state is held in instance fields, matching its intended use
 * as a {@code static final} field shared across a test class.
 */
public final class VertiqueAppExtension implements BeforeAllCallback, AfterAllCallback {

    /** The shared-data map name the framework's {@code HttpVerticle} publishes the bound port under. */
    private static final String SHARED_DATA_MAP = "vertique";

    /** The shared-data key holding the actual HTTP port once an HTTP server has bound. */
    private static final String HTTP_PORT_KEY = "http.port";

    private final VertiqueComponentFactory<? extends VertiqueApplicationComponent> factory;

    private JsonObject config = new JsonObject();
    private Duration startTimeout = Duration.ofSeconds(30);

    /** The supplied or extension-created Vert.x instance; {@code null} until {@code beforeAll}. */
    private Vertx vertx;

    /** Whether this extension created (and therefore must close) the {@link #vertx} instance. */
    private boolean ownsVertx;

    /**
     * The started-application handle; {@code null} until {@code beforeAll} completes. Typed with a
     * wildcard because the extension does not retain the factory's concrete component type — {@link
     * #component()} narrows it with a documented unchecked cast.
     */
    private VertiqueApplicationHandle<?> handle;

    private VertiqueAppExtension(VertiqueComponentFactory<? extends VertiqueApplicationComponent> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    // --- Factory + fluent configuration ---

    /**
     * Creates an extension that boots the application built by the given component factory.
     *
     * @param factory the factory that builds the application's {@link VertiqueApplicationComponent}
     *     from a {@link VertiqueRuntime}; must not be {@code null}
     * @return a new extension bound to the factory, with default empty config, a created-and-owned
     *     {@link Vertx}, and a 30-second start timeout
     * @throws NullPointerException if {@code factory} is {@code null}
     */
    public static VertiqueAppExtension forFactory(
            VertiqueComponentFactory<? extends VertiqueApplicationComponent> factory) {
        return new VertiqueAppExtension(factory);
    }

    /**
     * Sets the root configuration handed to the application at startup. Defaults to an empty {@link
     * JsonObject} when not called.
     *
     * @param config the root configuration; must not be {@code null}
     * @return this extension, for fluent chaining
     * @throws NullPointerException if {@code config} is {@code null}
     */
    public VertiqueAppExtension withConfig(JsonObject config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /**
     * Supplies a caller-owned {@link Vertx} instance to start the application on, instead of letting
     * the extension create one. When supplied, the extension does <em>not</em> close it on teardown
     * — the caller owns its lifecycle. By default the extension creates and owns a {@code Vertx},
     * closing it after the application shuts down.
     *
     * @param vertx the Vert.x instance to use; must not be {@code null}
     * @return this extension, for fluent chaining
     * @throws NullPointerException if {@code vertx} is {@code null}
     */
    public VertiqueAppExtension withVertx(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.ownsVertx = false;
        return this;
    }

    /**
     * Sets how long {@code beforeAll}/{@code afterAll} wait for startup and teardown to settle.
     * Defaults to 30 seconds.
     *
     * @param timeout the await timeout; must not be {@code null}
     * @return this extension, for fluent chaining
     * @throws NullPointerException if {@code timeout} is {@code null}
     */
    public VertiqueAppExtension startTimeout(Duration timeout) {
        this.startTimeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    // --- JUnit 5 lifecycle ---

    /**
     * Boots the application before any of the test class's tests run: creates a {@link Vertx} if none
     * was supplied (and marks it owned), then awaits {@link VertiqueApplicationBootstrap#start} up to
     * the start timeout and stores the resulting handle.
     *
     * @param context the JUnit extension context (unused)
     * @throws IllegalStateException if startup times out, is interrupted, or fails — the original
     *     cause is named and attached
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        if (vertx == null) {
            vertx = Vertx.vertx();
            ownsVertx = true;
        }
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, config);
        handle = await(VertiqueApplicationBootstrap.start(runtime, factory), "application start");
    }

    /**
     * Tears the application down after all of the test class's tests have run: awaits {@link
     * VertiqueApplicationHandle#shutdown()} first, then closes the {@link Vertx} instance if this
     * extension owns it. Failures are surfaced (not swallowed) so a broken teardown fails the build.
     *
     * <p>The owned-{@code Vertx} close always runs, even when the application shutdown step fails:
     * the shutdown failure is captured as the primary exception; if the close also fails its
     * exception is attached as a suppressed cause. All owned state ({@code handle}, {@code vertx},
     * {@code ownsVertx}) is reset in the {@code finally} block so the instance cannot restart
     * against a closed Vert.x.
     *
     * @param context the JUnit extension context (unused)
     * @throws IllegalStateException if shutdown or the owned-{@code Vertx} close times out, is
     *     interrupted, or fails — the most-relevant (primary) exception propagates; a secondary
     *     failure is attached as a suppressed cause
     */
    @Override
    public void afterAll(ExtensionContext context) {
        RuntimeException primary = null;
        try {
            if (handle != null) {
                await(handle.shutdown(), "application shutdown");
            }
        } catch (RuntimeException e) {
            primary = e;
        }
        try {
            if (ownsVertx && vertx != null) {
                await(vertx.close(), "owned Vertx close");
            }
        } catch (RuntimeException e) {
            if (primary == null) {
                primary = e;
            } else {
                primary.addSuppressed(e);
            }
        } finally {
            handle = null;
            vertx = null;
            ownsVertx = false;
        }
        if (primary != null) {
            throw primary;
        }
    }

    // --- Started-state accessors ---

    /**
     * Returns the {@link Vertx} instance the application was started on.
     *
     * @return the Vert.x instance; never {@code null}
     * @throws IllegalStateException if called before {@code beforeAll}
     */
    public Vertx vertx() {
        requireStarted();
        return vertx;
    }

    /**
     * Returns the started-application handle, exposing the built component and idempotent shutdown.
     *
     * @return the application handle; never {@code null}
     * @throws IllegalStateException if called before {@code beforeAll}
     */
    public VertiqueApplicationHandle<?> handle() {
        requireStarted();
        return handle;
    }

    /**
     * Returns the built application component, cast to the caller's expected component type.
     *
     * <p>The cast is unchecked: the extension does not retain the factory's concrete component type,
     * so the caller asserts the type it knows the factory produces. A mismatch surfaces as a {@link
     * ClassCastException} at the call site.
     *
     * @param <C> the application component type the factory built
     * @return the built component, cast to {@code C}; never {@code null}
     * @throws IllegalStateException if called before {@code beforeAll}
     * @throws ClassCastException if the built component is not assignable to {@code C}
     */
    @SuppressWarnings("unchecked")
    public <C extends VertiqueApplicationComponent> C component() {
        requireStarted();
        return (C) handle.component();
    }

    /**
     * Returns the configuration the application was (or will be) started with. Valid at any time —
     * before {@code beforeAll} it returns the configured value, defaulting to an empty {@link
     * JsonObject}.
     *
     * @return the root configuration; never {@code null}
     */
    public JsonObject config() {
        return config;
    }

    /**
     * Returns the actual HTTP port the application's HTTP server bound to, read from the framework's
     * {@code "vertique"} shared-data map (published by {@code HttpVerticle} after a successful bind).
     *
     * @return the bound HTTP port
     * @throws IllegalStateException if called before {@code beforeAll}, or if no port is present —
     *     the application started no HTTP server (a management-only app, or one with no EDGE
     *     {@code HttpVerticle})
     */
    public int httpPort() {
        requireStarted();
        Object port = vertx.sharedData().getLocalMap(SHARED_DATA_MAP).get(HTTP_PORT_KEY);
        if (port == null) {
            throw new IllegalStateException("No " + HTTP_PORT_KEY + " in sharedData(\"" + SHARED_DATA_MAP
                    + "\") — the application did not start an HTTP server (management-only / no EDGE HttpVerticle?)");
        }
        return ((Number) port).intValue();
    }

    // --- Private helpers ---

    /**
     * Guards the started-state accessors: throws a clear error when an accessor that needs a booted
     * application is called before {@code beforeAll} ran.
     *
     * @throws IllegalStateException if the application has not been started yet
     */
    private void requireStarted() {
        if (handle == null) {
            throw new IllegalStateException("VertiqueAppExtension not started — accessor called before beforeAll");
        }
    }

    /**
     * Blocks the (non-event-loop) JUnit thread until the given future settles, returning its result
     * or surfacing any failure as an {@link IllegalStateException} naming the awaited action. Shared
     * by startup, shutdown, and the owned-{@code Vertx} close so all three wrap and time out
     * identically.
     *
     * @param <T> the future's result type
     * @param future the future to await
     * @param action a short description of what is being awaited, for the error message
     * @return the future's successful result
     * @throws IllegalStateException if the future times out, is interrupted, or fails
     */
    private <T> T await(Future<T> future, String action) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(startTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(action + " did not complete within " + startTimeout, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(action + " failed: " + describeCause(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting " + action, e);
        }
    }

    /**
     * Renders a cause for an error message, tolerating a {@code null} cause.
     *
     * @param cause the throwable cause, possibly {@code null}
     * @return a human-readable description of the cause
     */
    private static String describeCause(Throwable cause) {
        return cause == null ? "unknown cause" : cause.toString();
    }
}
