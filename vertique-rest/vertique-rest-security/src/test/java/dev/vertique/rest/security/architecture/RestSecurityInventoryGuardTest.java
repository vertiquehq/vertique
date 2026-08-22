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
 * full generic signature. The direction is subset, so a recorded row for a member a later task has
 * not implemented yet does not fail; T036's cross-module union check is exact-set equality.
 */
class RestSecurityInventoryGuardTest {

    private static final String INVENTORY_RESOURCE = "mcp/architecture/rest-security-public-inventory.json";

    @Test
    void shouldExportOnlyRecordedGenericSignaturesForRestSecurity() {
        RestSecurityInventoryChecker checker = new RestSecurityInventoryChecker(SecurityPolicyEnforcer.class);
        JsonObject recorded = RestSecurityInventoryChecker.recordedInventory(INVENTORY_RESOURCE);

        Set<String> unrecordedSignatures = new TreeSet<>(checker.scannedSignatures());
        unrecordedSignatures.removeAll(RestSecurityInventoryChecker.recordedSignatures(recorded));

        Set<String> unrecordedPackages = new TreeSet<>(checker.scannedPackages());
        unrecordedPackages.removeAll(RestSecurityInventoryChecker.recordedPackages(recorded));

        assertThat(unrecordedSignatures)
                .as("public generic signatures exported by vertique-rest-security but absent from "
                        + INVENTORY_RESOURCE)
                .isEmpty();
        assertThat(unrecordedPackages)
                .as("packages declared by vertique-rest-security but absent from " + INVENTORY_RESOURCE)
                .isEmpty();
    }
}
