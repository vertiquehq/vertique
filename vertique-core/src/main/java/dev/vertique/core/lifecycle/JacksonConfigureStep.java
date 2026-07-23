// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import dev.vertique.core.json.JacksonConfigurer;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The framework's {@link LifecyclePhase#CONFIGURE CONFIGURE} startup step: applies all registered
 * {@link dev.vertique.core.json.ObjectMapperCustomizer} instances to the shared Vert.x
 * {@link io.vertx.core.json.jackson.DatabindCodec#mapper() ObjectMapper} by delegating to
 * {@link JacksonConfigurer#configure()}.
 *
 * <p>This step replaces the manual {@code c.jacksonConfigurer().configure()} call that applications
 * previously made by hand in {@code MainVerticle.start()}. It is contributed
 * {@code @IntoSet ApplicationStartupStep} by {@link CoreLifecycleStepsModule}, so the lifecycle
 * runner invokes it automatically in {@code CONFIGURE} phase order — before any verticle deploys.
 *
 * <p>{@link JacksonConfigurer#configure()} is idempotent, so re-running this step (or running it
 * alongside a lingering manual call during the additive migration window) takes effect only once.
 */
@Singleton
public final class JacksonConfigureStep implements ApplicationStartupStep {

    private final JacksonConfigurer jacksonConfigurer;

    /**
     * Constructs the step.
     *
     * @param jacksonConfigurer the configurer whose {@link JacksonConfigurer#configure()} this step
     *     runs in the {@link LifecyclePhase#CONFIGURE} phase
     */
    @Inject
    public JacksonConfigureStep(JacksonConfigurer jacksonConfigurer) {
        this.jacksonConfigurer = jacksonConfigurer;
    }

    /**
     * Returns the phase this step runs in.
     *
     * @return {@link LifecyclePhase#CONFIGURE}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.CONFIGURE;
    }

    /**
     * Applies the registered Jackson customizers to the Vert.x mapper, then completes.
     *
     * @return a succeeded future once {@link JacksonConfigurer#configure()} has run
     */
    @Override
    public Future<Void> start() {
        jacksonConfigurer.configure();
        return Future.succeededFuture();
    }
}
