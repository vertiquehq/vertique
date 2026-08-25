// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.security.SecurityPolicyEnforcer;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Progressive public-surface guard for {@code vertique-rest-security} (T005, inheriting T006's
 * frozen per-module guard mechanism).
 *
 * <p>This is the module's own guard: T005 adds the first post-rebaseline public member outside the
 * two MCP modules — {@link SecurityPolicyEnforcer#decide} — so it creates this local resource
 * covering {@code SecurityPolicyEnforcer}, {@code AuthorizationDecisionPoint},
 * {@code SyncPolicyDecisionPoint}, and {@code VertxProviderDecisionPoint} at their as-built
 * generic signatures, so any member drift beyond the one addition fails.
 *
 * <p>Compares every public member this module compiles against the committed local inventory at
 * full generic signature in both directions. From T036 onward, recorded and compiled sets must be
 * exactly equal, independently of the cross-module union check.
 */
class RestSecurityInventoryGuardTest {

    private static final String INVENTORY_RESOURCE = "mcp/architecture/rest-security-public-inventory.json";

    @Test
    void shouldExportOnlyRecordedGenericSignaturesForRestSecurity() {
        RestSecurityInventoryChecker checker = new RestSecurityInventoryChecker(SecurityPolicyEnforcer.class);
        JsonObject recorded = RestSecurityInventoryChecker.recordedInventory(INVENTORY_RESOURCE);

        Set<String> scannedSignatures = checker.scannedSignatures();
        Set<String> recordedSignatures = RestSecurityInventoryChecker.recordedSignatures(recorded);
        Set<String> unrecordedSignatures = new TreeSet<>(scannedSignatures);
        unrecordedSignatures.removeAll(recordedSignatures);
        Set<String> unimplementedSignatures = new TreeSet<>(recordedSignatures);
        unimplementedSignatures.removeAll(scannedSignatures);

        Set<String> scannedPackages = checker.scannedPackages();
        Set<String> recordedPackages = RestSecurityInventoryChecker.recordedPackages(recorded);
        Set<String> unrecordedPackages = new TreeSet<>(scannedPackages);
        unrecordedPackages.removeAll(recordedPackages);
        Set<String> unimplementedPackages = new TreeSet<>(recordedPackages);
        unimplementedPackages.removeAll(scannedPackages);

        assertThat(unrecordedSignatures)
                .as("public generic signatures exported by vertique-rest-security but absent from "
                        + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unimplementedSignatures)
                .as("recorded public generic signatures no longer exported by this module")
                .isEmpty();
        assertThat(unrecordedPackages)
                .as("packages declared by vertique-rest-security but absent from " + INVENTORY_RESOURCE)
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
