// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.tool.McpToolDescriptor;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Progressive public-surface guard for {@code vertique-mcp-core} (T006, TP-002).
 *
 * <p>Same comparison basis and subset direction as the sibling guard in {@code vertique-mcp-server},
 * driven by this module's own duplicated checker rather than a shared utility.
 */
class McpCoreInventoryGuardTest {

    private static final String INVENTORY_RESOURCE = "mcp/architecture/mcp-core-public-inventory.json";

    @Test
    void shouldExportOnlyRecordedGenericSignaturesForCore() {
        McpCoreInventoryChecker checker = new McpCoreInventoryChecker(McpToolDescriptor.class);
        JsonObject recorded = McpCoreInventoryChecker.recordedInventory(INVENTORY_RESOURCE);

        Set<String> unrecordedSignatures = new TreeSet<>(checker.scannedSignatures());
        unrecordedSignatures.removeAll(McpCoreInventoryChecker.recordedSignatures(recorded));

        Set<String> unrecordedPackages = new TreeSet<>(checker.scannedPackages());
        unrecordedPackages.removeAll(McpCoreInventoryChecker.recordedPackages(recorded));

        assertThat(unrecordedSignatures)
                .as("public generic signatures exported by vertique-mcp-core but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unrecordedPackages)
                .as("packages declared by vertique-mcp-core but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
    }
}
