// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.strategy;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * Test-source pass-through strategy carrying the built-in {@code "web-validation"} id, so
 * TP-009's and TP-010's rows can select it through {@code jaxrs.validationStrategy} without
 * requiring the real vertx-json-schema strategy. Installs no gate for any operation and reports
 * {@code resolvesOperationsFromMountContract()} {@code false} (the interface default): the
 * validator's location parse never runs under this strategy.
 */
public final class WebValidationPassThroughStrategy implements RequestValidationStrategy {

    @Override
    public String id() {
        return "web-validation";
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        return Optional.empty();
    }
}
