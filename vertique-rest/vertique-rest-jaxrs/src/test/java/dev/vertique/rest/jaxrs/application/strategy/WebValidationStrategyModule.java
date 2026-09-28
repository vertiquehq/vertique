// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.strategy;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;

/**
 * Binds a fresh {@link WebValidationPassThroughStrategy} into {@code Set<RequestValidationStrategy>}
 * so a component can select the built-in {@code "web-validation"} id (TP-009's row (a)) without the
 * real vertx-json-schema strategy.
 */
@Module
public final class WebValidationStrategyModule {

    private WebValidationStrategyModule() {}

    @Provides
    @IntoSet
    static RequestValidationStrategy webValidationPassThroughStrategy() {
        return new WebValidationPassThroughStrategy();
    }
}
