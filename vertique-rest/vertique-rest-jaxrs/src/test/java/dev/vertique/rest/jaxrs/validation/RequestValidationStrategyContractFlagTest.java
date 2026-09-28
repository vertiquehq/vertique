// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-021 (rest-jaxrs half): {@link RequestValidationStrategy#resolvesOperationsFromMountContract()}
 * is a {@code default} method of the SPI interface, defaulting to {@code false} (AR3-003), the
 * precedent set by {@link RequestValidationStrategy#runsFileVerifiers()}. The {@code true}-reporting
 * half, {@code OpenApiContractValidationStrategy}, is proved in {@code
 * OpenApiContractStrategyTest} (vertique-rest-openapi-validation).
 */
class RequestValidationStrategyContractFlagTest {

    @Test
    @DisplayName("resolvesOperationsFromMountContract() is a default interface method that defaults to false")
    void defaultIsFalse() throws NoSuchMethodException {
        RequestValidationStrategy minimal = new MinimalStrategy();
        RequestValidationStrategy none = new NoneValidationStrategy();

        assertFalse(
                minimal.resolvesOperationsFromMountContract(),
                "a strategy implementing only the abstract"
                        + " methods must not report resolving operations from the mount contract");
        assertFalse(
                none.resolvesOperationsFromMountContract(), "the built-in 'none' strategy must not report it either");

        Method method = RequestValidationStrategy.class.getMethod("resolvesOperationsFromMountContract");
        assertTrue(method.isDefault(), "resolvesOperationsFromMountContract() must be a default interface method");
    }

    /** A strategy implementing only {@link RequestValidationStrategy}'s two abstract methods. */
    private static final class MinimalStrategy implements RequestValidationStrategy {

        @Override
        public String id() {
            return "minimal";
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }
}
