// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteAuthHandlerOptionalContractTest {

    @Test
    void shouldDefaultToUnsupportedWithoutChangingRequiredHandler() {
        Handler<RoutingContext> required = context -> {};
        RouteAuthHandler requiredOnly = new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return "required";
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return required;
            }
        };
        Handler<RoutingContext> optional = context -> {};
        RouteAuthHandler optIn = new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return "optional";
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return required;
            }

            @Override
            public Optional<Handler<RoutingContext>> createOptionalHandler() {
                return Optional.of(optional);
            }
        };

        assertSame(required, requiredOnly.createHandler());
        assertTrue(requiredOnly.createOptionalHandler().isEmpty());
        assertSame(required, optIn.createHandler());
        assertSame(optional, optIn.createOptionalHandler().orElseThrow());
    }
}
