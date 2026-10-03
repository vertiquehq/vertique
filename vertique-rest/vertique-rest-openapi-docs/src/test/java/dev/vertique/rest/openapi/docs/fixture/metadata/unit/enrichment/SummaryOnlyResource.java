// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Operation;

/**
 * The control of {@link HiddenContractResource}: the same {@code @Operation(summary = "S")} method,
 * implementing no interface, so nothing hides it. Nothing invokes it; the class itself carries no
 * annotation.
 */
public final class SummaryOnlyResource {

    /** Declares only a summary. */
    @Operation(summary = "S")
    public void report() {}
}
