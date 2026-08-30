// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.Vertx;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R01 TP-002 — {@code shouldRejectRequiresActionToolsWithNoAuthorizerAtMount} (issue #421).
 *
 * <p>Before this repair, a registry containing an {@code @RequiresAction} tool with no core {@link
 * dev.vertique.security.authz.Authorizer} installed mounted successfully; the first request against
 * that tool then NPE'd inside {@code SecurityPolicyEnforcer.decide}, which the surrounding fail-closed
 * catch converted into a deny. Safe, but accidental — REST has an equivalent startup gate ({@code
 * JaxRsRouteRegistrar#resolveRequiredAction}'s "No Authorizer" case) and MCP did not.
 *
 * <p>Given a registry containing one {@code @RequiresAction}-equivalent tool and no installed core
 * {@link dev.vertique.security.authz.Authorizer}. Framework wiring — composition, descriptors, and the
 * fixture invoker/authorizer doubles — lives in {@link McpActionAuthorizerStartupTestFixture}; this
 * class keeps only the Given values, the one action, and the decisive assertions.
 */
@DisplayName("MCP @RequiresAction-without-Authorizer startup validation — R01 TP-002 (issue #421)")
class McpActionAuthorizerStartupTest {

    private static final String ACTION_TOOL_NAME = "action.tool";

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("shouldRejectRequiresActionToolsWithNoAuthorizerAtMount")
    void shouldRejectRequiresActionToolsWithNoAuthorizerAtMount() {
        McpActionAuthorizerStartupTestFixture.ComposeResult result = McpActionAuthorizerStartupTestFixture.compose(
                vertx,
                McpActionAuthorizerStartupTestFixture.oneRequiresActionInvoker(ACTION_TOOL_NAME),
                McpActionAuthorizerStartupTestFixture.enabledConfig(),
                Optional.empty());

        assertThat(result.startupErrorCount())
                .as("exactly one bounded configuration error")
                .isEqualTo(1);
        assertThat(result.mountedRouteCount())
                .as("DECISIVE: no route may mount — this must distinguish \"failed before mounting\" from "
                        + "\"mounted then failed\", which an exception-only assertion cannot")
                .isZero();
        assertThat(result.failure())
                .as("the error must name the affected tool")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(ACTION_TOOL_NAME);
    }

    /**
     * Sensitivity (T005-style pairing, per R01's standing requirement): installing an {@link
     * dev.vertique.security.authz.Authorizer} flips the same composition from rejected to mounted — the
     * error count goes 1→0 and the mounted-route count goes 0→1.
     */
    @Test
    @DisplayName("shouldMountWhenAnAuthorizerIsInstalled")
    void shouldMountWhenAnAuthorizerIsInstalled() {
        McpActionAuthorizerStartupTestFixture.ComposeResult result = McpActionAuthorizerStartupTestFixture.compose(
                vertx,
                McpActionAuthorizerStartupTestFixture.oneRequiresActionInvoker(ACTION_TOOL_NAME),
                McpActionAuthorizerStartupTestFixture.enabledConfig(),
                Optional.of(McpActionAuthorizerStartupTestFixture.installedAuthorizer()));

        assertThat(result.startupErrorCount())
                .as("no configuration error once an Authorizer is installed")
                .isZero();
        assertThat(result.mountedRouteCount())
                .as("DECISIVE: the same @RequiresAction tool now mounts")
                .isEqualTo(1);
        assertThat(result.failure()).isNull();
    }
}
