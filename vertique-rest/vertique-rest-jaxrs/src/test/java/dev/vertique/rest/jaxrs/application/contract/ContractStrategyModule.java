// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.application.strategy.OpenApiContractPassThroughStrategy;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import jakarta.inject.Singleton;

/**
 * TP-007's strategy module: binds one {@code @Singleton} {@link OpenApiContractPassThroughStrategy}
 * instance both into {@code Set<RequestValidationStrategy>} and as its own provision, so the test
 * can read the same instance's recorded locations after deployment.
 */
@Module
public final class ContractStrategyModule {

    private ContractStrategyModule() {}

    @Provides
    @Singleton
    static OpenApiContractPassThroughStrategy openApiContractPassThroughStrategy() {
        return new OpenApiContractPassThroughStrategy();
    }

    @Provides
    @IntoSet
    static RequestValidationStrategy requestValidationStrategy(OpenApiContractPassThroughStrategy strategy) {
        return strategy;
    }
}
