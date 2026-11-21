// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.extension.ExtensionPhase;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpFailureOwnershipTest {

    @Test
    @DisplayName("a disabled MCP server registers nothing on the main router")
    void disabledServerRegistersNothing() {
        Vertx vertx = Vertx.vertx();
        try {
            McpFailureOwnership ownership = new McpFailureOwnership(
                    McpServerConfig.builder().enabled(false).build(), null);
            Router router = Router.router(vertx);

            ownership.customize(router);

            assertThat(router.getRoutes())
                    .as("routes registered by a disabled server")
                    .isEmpty();
        } finally {
            vertx.close();
        }
    }

    @Test
    @DisplayName("the customizer sorts ahead of every application customizer")
    void sortsAheadOfApplicationCustomizers() {
        McpFailureOwnership ownership =
                new McpFailureOwnership(McpServerConfig.builder().build(), null);

        assertThat(ownership.phase()).as("phase").isEqualTo(ExtensionPhase.SYSTEM_FIRST);
        assertThat(ownership.priority()).as("priority").isEqualTo(Integer.MIN_VALUE);
    }
}
