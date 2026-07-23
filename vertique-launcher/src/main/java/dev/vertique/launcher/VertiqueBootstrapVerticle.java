// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework-owned standalone entry verticle.
 *
 * <p>{@code VertiqueApplication} supplies this verticle via
 * {@link VertiqueApplication#verticleSupplier()}, so an ordinary standalone application needs
 * <em>no</em> {@code MainVerticle} and <em>no</em> {@code Main-Verticle} manifest entry. On
 * {@link #start(Promise)} it:
 * <ol>
 *   <li>builds a neutral {@link VertiqueRuntime} from the deployed {@link #getVertx() Vert.x}
 *       instance and the canonical merged {@link #config()} (installed by
 *       {@link VertiqueApplication#beforeDeployingVerticle});</li>
 *   <li>discovers the application's single {@link VertiqueComponentFactory} via
 *       {@link VertiqueComponentFactoryLoader#discover()} (exactly-one, fail-fast otherwise);</li>
 *   <li>drives the host-neutral lifecycle through
 *       {@link VertiqueApplicationBootstrap#start(VertiqueRuntime, VertiqueComponentFactory)},
 *       completing the start promise on success and failing it (with the original cause) on any
 *       startup failure.</li>
 * </ol>
 *
 * <p>On {@link #stop(Promise)} it tears down the application via the returned
 * {@link VertiqueApplicationHandle#shutdown()} (idempotent reverse-order teardown). The handle
 * <strong>never</strong> closes the {@code Vertx} instance — the launcher owns the {@code Vertx}
 * lifecycle and closes it after this verticle stops.
 *
 * <p>The class is {@code final} and framework-owned; applications do not subclass it. Custom-startup
 * apps opt out of the framework supplier via the {@code vertique.bootstrap.verticle=false} system
 * property (see {@link VertiqueApplication#verticleSupplier()}) and supply their own main verticle.
 */
public final class VertiqueBootstrapVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(VertiqueBootstrapVerticle.class);

    /**
     * The handle returned by the lifecycle runner once startup succeeds; {@code null} until then (or
     * when startup failed). Read in {@link #stop(Promise)} to drive teardown. Written and read on the
     * verticle's own event-loop context, so no synchronization is needed.
     *
     * <p>Typed as {@code VertiqueApplicationHandle<?>}: this verticle only calls {@link
     * VertiqueApplicationHandle#shutdown()}, so it never needs the concrete component type.
     */
    private VertiqueApplicationHandle<?> handle;

    /**
     * The factory-discovery seam, invoked once from {@link #start(Promise)}. In production this is
     * {@link VertiqueComponentFactoryLoader#discover}; tests inject a throwing supplier to exercise
     * the discovery failure path (e.g. a {@link java.util.ServiceConfigurationError}) without needing
     * a malformed real {@code META-INF/services} entry on the classpath.
     */
    private final Supplier<VertiqueComponentFactory<VertiqueApplicationComponent>> discovery;

    /**
     * Production constructor: discovers the application factory via
     * {@link VertiqueComponentFactoryLoader#discover}. This is the constructor the framework supplier
     * ({@link VertiqueApplication#verticleSupplier()}) uses.
     */
    public VertiqueBootstrapVerticle() {
        this(VertiqueComponentFactoryLoader::discover);
    }

    /**
     * Package-private constructor seam: discovers the application factory via the supplied function.
     * Used by tests to inject a discovery that throws (e.g. a {@link java.util.ServiceConfigurationError})
     * so the discovery failure path can be exercised deterministically.
     *
     * @param discovery the factory-discovery seam invoked from {@link #start(Promise)}; never {@code null}
     */
    VertiqueBootstrapVerticle(Supplier<VertiqueComponentFactory<VertiqueApplicationComponent>> discovery) {
        this.discovery = discovery;
    }

    /**
     * Builds the runtime, discovers the application factory, and drives the host-neutral lifecycle.
     *
     * <p>The discovery and runner build run on the verticle's deployment context. A discovery failure
     * (zero or multiple factories) or any startup-phase failure fails {@code startPromise} with the
     * original cause, which propagates to the launcher and maps to exit code {@code 15} (deployment).
     *
     * @param startPromise completed when the application has fully started, or failed with the
     *     original startup cause; never {@code null} in production
     */
    @Override
    public void start(Promise<Void> startPromise) {
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, config());

        VertiqueComponentFactory<VertiqueApplicationComponent> factory;
        try {
            factory = discovery.get();
        } catch (Throwable e) {
            // Catch Throwable (not just RuntimeException) so a malformed META-INF/services entry — which
            // surfaces as java.util.ServiceConfigurationError, an Error, not a RuntimeException — yields
            // the guided discovery-failure handling and a clean failed start promise rather than
            // propagating uncaught. Mirrors VertiqueApplication.createVertxBuilder, which catches
            // Throwable to handle the same ServiceConfigurationError from contributor discovery.
            log.error("Application factory discovery failed: {}", e.getMessage());
            startPromise.fail(e);
            return;
        }

        VertiqueApplicationBootstrap.start(runtime, factory).onComplete(ar -> {
            if (ar.succeeded()) {
                this.handle = ar.result();
                startPromise.complete();
            } else {
                startPromise.fail(ar.cause());
            }
        });
    }

    /**
     * Tears down the application via the runner-returned handle, if startup succeeded.
     *
     * <p>When {@link #handle} is non-null its idempotent {@link VertiqueApplicationHandle#shutdown()}
     * runs the shutdown steps in reverse and undeploys the application's verticles, then completes
     * {@code stopPromise}. When startup never produced a handle (discovery or a startup phase failed),
     * teardown is a no-op and the promise completes immediately. The {@code Vertx} instance is never
     * closed here — the launcher owns it.
     *
     * @param stopPromise completed when teardown has settled; never {@code null} in production
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        if (handle != null) {
            handle.shutdown().onComplete(stopPromise);
        } else {
            stopPromise.complete();
        }
    }
}
