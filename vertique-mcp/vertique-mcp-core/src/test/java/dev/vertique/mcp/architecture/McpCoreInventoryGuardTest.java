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
 * <p>Uses the same full-generic-signature, exact-set comparison as the sibling guard in {@code
 * vertique-mcp-server}, driven by this module's own duplicated checker rather than a shared utility.
 */
class McpCoreInventoryGuardTest {

    private static final String INVENTORY_RESOURCE = "mcp/architecture/mcp-core-public-inventory.json";

    @Test
    void shouldExportOnlyRecordedGenericSignaturesForCore() {
        McpCoreInventoryChecker checker = new McpCoreInventoryChecker(McpToolDescriptor.class);
        JsonObject recorded = McpCoreInventoryChecker.recordedInventory(INVENTORY_RESOURCE);

        Set<String> scannedSignatures = checker.scannedSignatures();
        Set<String> recordedSignatures = McpCoreInventoryChecker.recordedSignatures(recorded);
        Set<String> unrecordedSignatures = new TreeSet<>(scannedSignatures);
        unrecordedSignatures.removeAll(recordedSignatures);
        Set<String> unimplementedSignatures = new TreeSet<>(recordedSignatures);
        unimplementedSignatures.removeAll(scannedSignatures);

        Set<String> scannedTypes = checker.scannedTypes();
        Set<String> recordedTypes = McpCoreInventoryChecker.recordedTypes(recorded);
        Set<String> unrecordedTypes = new TreeSet<>(scannedTypes);
        unrecordedTypes.removeAll(recordedTypes);
        Set<String> unimplementedTypes = new TreeSet<>(recordedTypes);
        unimplementedTypes.removeAll(scannedTypes);

        Set<String> scannedPackages = checker.scannedPackages();
        Set<String> recordedPackages = McpCoreInventoryChecker.recordedPackages(recorded);
        Set<String> unrecordedPackages = new TreeSet<>(scannedPackages);
        unrecordedPackages.removeAll(recordedPackages);
        Set<String> unimplementedPackages = new TreeSet<>(recordedPackages);
        unimplementedPackages.removeAll(scannedPackages);

        assertThat(unrecordedSignatures)
                .as("public generic signatures exported by vertique-mcp-core but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unimplementedSignatures)
                .as("recorded public generic signatures no longer exported by this module")
                .isEmpty();
        assertThat(unrecordedTypes)
                .as("public types exported by vertique-mcp-core but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unimplementedTypes)
                .as("recorded public types no longer exported by vertique-mcp-core")
                .isEmpty();
        assertThat(unrecordedPackages)
                .as("packages declared by vertique-mcp-core but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unimplementedPackages)
                .as("recorded packages no longer exported by this module")
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
