// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.security.RouteAuthHandler;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the stable, operator-visible bounds of {@link McpServerConfig}. */
class McpServerConfigTest {
    private final McpServerConfigValidator validator = new McpServerConfigValidator();

    @Test
    @DisplayName("accepts enabled configuration at documented bounds")
    void shouldValidateEveryBoundAndOptionalAuthenticationCombination() {
        McpServerConfig configuration = McpServerConfig.builder()
                .enabled(true)
                .serverName("server")
                .serverVersion("1.0")
                .build();

        assertThatCode(() -> validator.validate(configuration)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                        configuration.toBuilder().requestTimeoutMs(999).build()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.request.timeoutMs");
        assertThatThrownBy(() -> validator.validate(
                        configuration.toBuilder().authenticationScheme(" ").build()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.authenticationScheme");

        McpServerConfig authenticated =
                configuration.toBuilder().authenticationScheme("bearer").build();
        assertThatCode(() -> validator.validate(authenticated, Set.of(optionalBearerHandler())))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(authenticated, Set.of()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("mcp.authenticationScheme");
    }

    private static RouteAuthHandler optionalBearerHandler() {
        return new RouteAuthHandler() {
            @Override
            public String schemeName() {
                return "bearer";
            }

            @Override
            public Handler<RoutingContext> createHandler() {
                return context -> context.next();
            }

            @Override
            public Optional<Handler<RoutingContext>> createOptionalHandler() {
                return Optional.of(context -> context.next());
            }
        };
    }
}
