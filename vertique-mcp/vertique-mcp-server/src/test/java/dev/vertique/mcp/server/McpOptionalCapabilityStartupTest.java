// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T010 TP-002 — a configured authentication scheme whose selected {@link
 * dev.vertique.rest.core.security.RouteAuthHandler} does not expose the optional-authentication
 * capability fails composition, before any route mounts.
 *
 * <p>Given a registry containing one {@code @RolesAllowed}-equivalent restricted tool and a
 * configured scheme whose {@code createOptionalHandler()} capability is absent (the interface
 * default). Framework wiring — composition, descriptors, and the fixture invoker/handler doubles —
 * lives in {@link McpOptionalCapabilityStartupTestFixture}; this method keeps only the Given values,
 * the one action, and the decisive assertions.
 *
 * <p><strong>Deviation from the task's stated test location:</strong> this class lives in {@code
 * dev.vertique.mcp.server}, not {@code dev.vertique.mcp.server.runtime}, for the same package-private
 * access reason recorded on {@link McpSchemaStartupTest}.
 */
@DisplayName("MCP optional-authentication capability startup validation — T010 TP-002")
class McpOptionalCapabilityStartupTest {

    private static final String SELECTED_SCHEME = "selected-scheme";

    private final Vertx vertx = Vertx.vertx();

    /** Closes the owned {@link Vertx}, waiting for its teardown to settle. */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(ignored -> closed.complete(null));
        closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("shouldRejectSelectedNonOptionalScheme")
    void shouldRejectSelectedNonOptionalScheme() {
        McpOptionalCapabilityStartupTestFixture.ComposeResult result = McpOptionalCapabilityStartupTestFixture.compose(
                vertx,
                McpOptionalCapabilityStartupTestFixture.oneRolesAllowedInvoker(),
                McpOptionalCapabilityStartupTestFixture.enabledConfigWithScheme(SELECTED_SCHEME),
                McpOptionalCapabilityStartupTestFixture.nonOptionalSchemeHandlers(SELECTED_SCHEME));

        assertThat(result.startupErrorCount())
                .as("exactly one bounded configuration error")
                .isEqualTo(1);
        assertThat(result.mountedRouteCount()).as("no route mounts").isZero();
        assertThat(result.failure())
                .as("the error must name the offending scheme configuration")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("authenticationScheme");
    }
}
