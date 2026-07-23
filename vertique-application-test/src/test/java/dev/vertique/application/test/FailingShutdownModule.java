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
import io.vertx.core.Promise;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Dagger {@code @Module} used exclusively by the {@code afterAll}-teardown-path self-test to
 * verify that the owned {@link io.vertx.core.Vertx} is closed even when application shutdown
 * stalls past the configured timeout.
 *
 * <p>The contributed {@link ApplicationShutdownStep} returns a future that never completes when
 * {@link #stallShutdown} is {@code true}, causing {@link
 * dev.vertique.application.test.VertiqueAppExtension#afterAll} to time out (the shutdown await
 * throws {@link IllegalStateException}). After the timeout the extension must still close the
 * owned Vert.x — that is the invariant under test.
 *
 * <p>Note: the framework's {@link
 * dev.vertique.application.VertiqueApplicationHandle#teardown()} swallows individual shutdown-step
 * failures via {@code recover()}, so a step returning a <em>failed</em> future would not surface
 * as a shutdown timeout. This module therefore stalls the shutdown future by never completing it,
 * which does cause the framework's {@code teardown()} chain to hang, triggering the timeout.
 *
 * <p>This flag is set per test so the module does not affect other tests that share the same
 * classloader.
 */
@Module
abstract class FailingShutdownModule {

    /**
     * When {@code true}, the contributed shutdown step returns a future that never completes,
     * causing the shutdown to stall and the extension to time out. Reset to {@code false} between
     * tests.
     */
    static final AtomicBoolean stallShutdown = new AtomicBoolean(false);

    @Multibinds
    abstract Set<ApplicationStartupStep> startupSteps();

    @Multibinds
    abstract Set<VerticleDeployment> verticleDeployments();

    /**
     * Contributes a single shutdown step that returns a never-completing future when {@link
     * #stallShutdown} is {@code true}. This causes the extension's shutdown await to time out,
     * surfacing as an {@link IllegalStateException} from {@code afterAll}.
     *
     * @return the shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep stallingShutdownStep() {
        return new ApplicationShutdownStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public Future<Void> stop() {
                if (stallShutdown.get()) {
                    // Return a future that never completes; the extension's await will time out.
                    return Promise.<Void>promise().future();
                }
                return Future.succeededFuture();
            }
        };
    }
}
