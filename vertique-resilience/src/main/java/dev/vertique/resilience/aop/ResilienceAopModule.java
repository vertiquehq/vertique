// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.aop;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.aop.AspectProvider;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.resilience.annotation.Resilient;
import io.vertx.core.Future;

/** Dagger bindings for the {@link Resilient} resilience aspect and its lifecycle owner. */
@Module
public abstract class ResilienceAopModule {

    @Binds
    abstract AspectProvider<Resilient> bindResilientAspect(ResilientAspect aspect);

    /** Contributes shutdown for the aspect-owned adapter context. */
    @Provides
    @IntoSet
    static ApplicationShutdownStep resilienceAopShutdownStep(ResilientAspect aspect) {
        return new ResilienceAopShutdownStep(aspect);
    }

    private static final class ResilienceAopShutdownStep implements ApplicationShutdownStep {

        private final ResilientAspect aspect;

        private ResilienceAopShutdownStep(ResilientAspect aspect) {
            this.aspect = aspect;
        }

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.VALIDATE;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public String orderKey() {
            return ResilienceAopShutdownStep.class.getName();
        }

        @Override
        public Future<Void> stop() {
            return aspect.close();
        }
    }
}
