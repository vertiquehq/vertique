// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import io.vertx.core.Deployable;
import io.vertx.core.Vertx;
import io.vertx.launcher.application.HookContext;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Test subclass of {@link VertiqueApplication} with {@code exitOnFailure=false} so that launch
 * tests do not call {@link System#exit} and can inspect the returned exit code directly.
 *
 * <p>Overrides {@link #afterVertxStarted(HookContext)} to capture the live {@link Vertx} instance
 * into {@link #capturedVertx} so that {@code @AfterEach} teardown can close it and wait for
 * graceful shutdown.
 *
 * <p>It also opts out of the framework-owned standalone bootstrap verticle by overriding
 * {@link #verticleSupplier()} to return {@code null} (the FR-APP-030 escape hatch). The launch tests
 * pass an explicit verticle FQN as a CLI positional argument (e.g. {@code NoopVerticle}) and exercise
 * the launcher's contributor-chain, config-resolution, overlay, and exit-code behaviour through that
 * CLI/{@code Main-Verticle} path; the framework supplier (a {@link VertiqueBootstrapVerticle}) would
 * bypass that path entirely. The default-supplier behaviour itself is covered by
 * {@link VertiqueApplicationVerticleSupplierTest} using the production base class.
 */
final class TestVertiqueApplication extends VertiqueApplication {

    /**
     * Holds the {@link Vertx} instance captured from the most recent
     * {@link #afterVertxStarted(HookContext)} callback. Tests use this to close the instance in
     * {@code @AfterEach}.
     */
    static final AtomicReference<Vertx> capturedVertx = new AtomicReference<>(null);

    /**
     * Creates an instance with {@code printUsageOnFailure=true} and {@code exitOnFailure=false}.
     * The {@code exitOnFailure=false} prevents {@link System#exit} during tests so that the
     * returned exit code can be inspected.
     *
     * @param args the command-line arguments
     */
    TestVertiqueApplication(String[] args) {
        super(args, true, false);
    }

    /**
     * Captures the {@link Vertx} instance from the hook context for later teardown.
     *
     * <p>{@link VertiqueApplication} does not currently override {@link #afterVertxStarted}, so
     * there is no meaningful {@code super} call; the interface default is a no-op.
     *
     * @param ctx the hook context providing the live {@link Vertx} instance; never {@code null}
     */
    @Override
    public void afterVertxStarted(HookContext ctx) {
        capturedVertx.set(ctx.vertx());
    }

    /**
     * Opts out of the framework-owned bootstrap verticle (FR-APP-030 escape hatch) so the launch
     * tests' explicit CLI verticle argument is resolved the standard way. Returns {@code null}
     * unconditionally rather than consulting the {@code vertique.bootstrap.verticle} system property,
     * so these tests never depend on ambient process state.
     *
     * @return {@code null}, deferring to the launcher's CLI/{@code Main-Verticle} resolution
     */
    @Override
    public Supplier<? extends Deployable> verticleSupplier() {
        return null;
    }
}
