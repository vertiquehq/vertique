// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Operation;

/**
 * An interface whose method declares {@code @Operation(hidden = true)}; {@link
 * HiddenContractResource} implements it with its own {@code @Operation}. Nothing invokes it.
 */
public interface HiddenContractApi {

    /** Hidden by the interface method's {@code @Operation}. */
    @Operation(hidden = true)
    void report();
}
