// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RequestValidationStrategySelector} resolves a configured strategy id against the
 * registered set by {@link RequestValidationStrategy#id()}, and fails fast with a
 * {@link RestConfigurationException} listing the available ids when none matches (no silent fallback,
 * no {@code "openapi-contract"} special-casing).
 */
class RequestValidationStrategySelectorTest {

    // --- Stub strategy ---

    private static RequestValidationStrategy strategy(String id) {
        return new RequestValidationStrategy() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
                return Optional.empty();
            }
        };
    }

    @Test
    @DisplayName("select returns the strategy whose id matches the configured id")
    void requestValidationStrategySelectorPicksByConfiguredId() {
        RequestValidationStrategy web = strategy("web-validation");
        RequestValidationStrategy none = strategy("none");
        Set<RequestValidationStrategy> available = Set.of(web, none);

        RequestValidationStrategy selected = RequestValidationStrategySelector.select("none", available);

        assertSame(none, selected, "select(\"none\", ...) must return the strategy whose id() is \"none\"");
    }

    @Test
    @DisplayName("select throws a startup exception naming the unknown id and listing the available ids")
    void requestValidationStrategySelectorFailsFastOnUnknownId() {
        Set<RequestValidationStrategy> available = Set.of(strategy("web-validation"), strategy("none"));

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> RequestValidationStrategySelector.select("nonexistent-typo", available),
                "an unknown configured id must fail fast with RestConfigurationException");

        String message = ex.getMessage();
        assertTrue(message.contains("nonexistent-typo"), "message must name the configured id; was: " + message);
        assertTrue(
                message.contains("[none, web-validation]"),
                "message must list the available ids sorted; was: " + message);
        assertFalse(
                message.contains("openapi-contract"),
                "message must not special-case or mention \"openapi-contract\"; was: " + message);
    }
}
