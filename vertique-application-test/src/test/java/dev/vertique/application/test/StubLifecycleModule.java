// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application.test;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import io.vertx.core.Future;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal Dagger {@code @Module} that satisfies {@link
 * dev.vertique.application.VertiqueApplicationComponent}'s accessors for the self-test: an empty
 * {@code Set<ApplicationStartupStep>}, an empty {@code Set<VerticleDeployment>} (so the {@code @Inject
 * VerticleDeploymentManager(VerticleDeployer, Set<VerticleDeployment>)} resolves, with {@code
 * VerticleDeployer} injected from {@code VertxModule}'s {@code Vertx}), and a single contributed
 * {@link ApplicationShutdownStep} that flips {@link #shutdownStepRan} so a test can prove the
 * extension actually ran the framework-owned teardown.
 *
 * <p>The startup multibinding is left empty; the shutdown step is contributed so its side-effect is
 * observable after teardown.
 */
@Module
abstract class StubLifecycleModule {

    /** Flipped to {@code true} when the contributed shutdown step runs; reset per test as needed. */
    static final AtomicBoolean shutdownStepRan = new AtomicBoolean(false);

    @Multibinds
    abstract Set<ApplicationStartupStep> startupSteps();

    @Multibinds
    abstract Set<VerticleDeployment> verticleDeployments();

    /**
     * Contributes a single shutdown step that records, via {@link #shutdownStepRan}, that the
     * framework-owned teardown ran. Bound in the non-verticle {@link LifecyclePhase#AFTER_START}
     * phase so it always runs for a fully started app.
     *
     * @return the shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep recordingShutdownStep() {
        return new ApplicationShutdownStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public Future<Void> stop() {
                shutdownStepRan.set(true);
                return Future.succeededFuture();
            }
        };
    }
}
