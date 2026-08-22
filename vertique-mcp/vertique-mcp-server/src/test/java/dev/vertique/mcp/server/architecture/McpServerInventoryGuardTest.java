// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.McpServerConfig;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Progressive public-surface guard for {@code vertique-mcp-server} (T006, TP-001).
 *
 * <p>Compares every public member this module compiles against the committed local inventory at
 * full generic signature. The direction is subset, so a recorded row for a member a later task has
 * not implemented yet does not fail; from T036 the comparison becomes exact-set equality.
 */
class McpServerInventoryGuardTest {

    private static final String INVENTORY_RESOURCE = "mcp/architecture/mcp-server-public-inventory.json";

    @Test
    void shouldExportOnlyRecordedGenericSignatures() {
        McpServerInventoryChecker checker = new McpServerInventoryChecker(McpServerConfig.class);
        JsonObject recorded = McpServerInventoryChecker.recordedInventory(INVENTORY_RESOURCE);

        Set<String> unrecordedSignatures = new TreeSet<>(checker.scannedSignatures());
        unrecordedSignatures.removeAll(McpServerInventoryChecker.recordedSignatures(recorded));

        Set<String> unrecordedPackages = new TreeSet<>(checker.scannedPackages());
        unrecordedPackages.removeAll(McpServerInventoryChecker.recordedPackages(recorded));

        assertThat(unrecordedSignatures)
                .as("public generic signatures exported by vertique-mcp-server but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unrecordedPackages)
                .as("packages declared by vertique-mcp-server but absent from " + INVENTORY_RESOURCE)
                .isEmpty();

        assertThat(checker.publicTypesWithNonPublicModuleSupertypes())
                .as("a public type extending a non-public module type leaks that type's public members past the guard")
                .isEmpty();

        assertThat(checker.excludedAsGenerated())
                .as("types excluded as annotation-processor output; an unexpected name here means real "
                        + "surface was silently dropped from the guard")
                .allSatisfy(name -> assertThat(simpleNameOf(name))
                        .as("excluded type %s does not look like annotation-processor output", name)
                        .matches("^(Dagger[A-Za-z0-9_$]*|[A-Za-z0-9$]+_[A-Za-z0-9_$]+)$"));
    }

    /**
     * The simple name of a binary name, used to judge whether an excluded type looks like
     * annotation-processor output. Dagger names its output {@code Dagger<Component>},
     * {@code <Type>_Factory}, {@code <Module>_<Method>Factory}, or {@code <Type>_MembersInjector} —
     * all of which either start with {@code Dagger} or carry an underscore, which this repository's
     * hand-authored type names never do.
     *
     * @param binaryName the excluded type's binary name; must not be {@code null}
     * @return its simple name
     */
    private static String simpleNameOf(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }
}
