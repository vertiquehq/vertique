// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;

/**
 * Dagger module for the opt-in OpenAPI-contract request-validation strategy ({@code openapi-contract}).
 *
 * <p>Contributes {@link OpenApiContractValidationStrategy} into the
 * {@code Set<RequestValidationStrategy>} multibinding (declared by the JAX-RS runtime module). When an
 * application depends on {@code vertique-rest-openapi-validation} and includes this module, the strategy
 * set contains an entry whose {@link RequestValidationStrategy#id()} is {@code "openapi-contract"}, so
 * selecting that id via {@code jaxrs.validationStrategy} resolves to this strategy.
 *
 * <p>This module is the only place the preview {@code vertx-openapi} artifact enters the
 * <em>production</em> graph — the default validation path ({@code web-validation} / {@code none})
 * never references it (FR-025). ({@code vertx-web-openapi-router} appears as a test-scope dependency
 * in {@code vertique-rest-security} and {@code vertique-rest-auth-jwt} for legacy IT support.)
 */
@Module
public abstract class OpenApiContractValidationModule {

    /**
     * Contributes the {@code openapi-contract} {@link RequestValidationStrategy} into the strategy
     * multibinding.
     *
     * @param strategy the OpenAPI-contract validation strategy
     * @return the strategy as a {@link RequestValidationStrategy} set element
     */
    @Binds
    @IntoSet
    abstract RequestValidationStrategy openApiContractValidationStrategy(OpenApiContractValidationStrategy strategy);
}
