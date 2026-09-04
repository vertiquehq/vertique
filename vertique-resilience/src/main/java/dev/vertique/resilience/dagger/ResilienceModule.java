// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.dagger;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.spi.ResilienceObserver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.Set;

/** Dagger bindings for the application-scoped resilience runtime and its lifecycle shutdown. */
@Module
public abstract class ResilienceModule {

    /** Prevents direct construction of the static binding module. */
    private ResilienceModule() {}

    @BindsOptionalOf
    abstract ResiliencePolicyRegistry resiliencePolicyRegistry();

    /** Declares the optional application-contributed resilience observer set. */
    @Multibinds
    abstract Set<ResilienceObserver> resilienceObservers();

    /**
     * Provides the one runtime owned by the application graph.
     *
     * @param vertx application Vert.x instance
     * @return application-scoped resilience runtime
     */
    @Provides
    @Singleton
    static Resilience resilience(Vertx vertx, Set<ResilienceObserver> observers) {
        return Resilience.create(vertx, observers);
    }

    /**
     * Contributes the runtime shutdown step to the host lifecycle.
     *
     * @param resilience application runtime
     * @return lifecycle-owned shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep resilienceShutdownStep(Resilience resilience) {
        return new ResilienceShutdownStep(resilience);
    }

    private static final class ResilienceShutdownStep implements ApplicationShutdownStep {

        private final Resilience resilience;

        private ResilienceShutdownStep(Resilience resilience) {
            this.resilience = resilience;
        }

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.CONFIGURE;
        }

        @Override
        public int priority() {
            return Integer.MAX_VALUE;
        }

        @Override
        public String orderKey() {
            return ResilienceShutdownStep.class.getName();
        }

        @Override
        public Future<Void> stop() {
            return resilience.close();
        }
    }
}
