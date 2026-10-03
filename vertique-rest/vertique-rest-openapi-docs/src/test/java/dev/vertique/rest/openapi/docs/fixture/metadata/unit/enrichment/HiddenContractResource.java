// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Implements {@link HiddenContractApi}: the implementation method declares only
 * {@code @Operation(summary = "S")}, while the interface method it overrides declares
 * {@code @Operation(hidden = true)}. Both instances reach the operation's resolved method annotations.
 * Nothing invokes it; the class itself carries no annotation.
 */
public final class HiddenContractResource implements HiddenContractApi {

    @Override
    @Operation(summary = "S")
    public void report() {}
}
