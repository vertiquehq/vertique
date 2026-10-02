// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The JSON request body of {@code createOrder}: the required {@code sku}, which matches {@value
 * #SKU_PATTERN}, and an optional {@code quantity}.
 *
 * @param sku      the ordered stock-keeping unit, required
 * @param quantity the ordered quantity, or {@code null} when absent
 */
public record PartnerOrderRequest(
        @NotNull @Pattern(regexp = SKU_PATTERN) String sku, Integer quantity) {

    /** The pattern every {@code sku} matches, as the partner contracts state it. */
    public static final String SKU_PATTERN = "^[A-Z]{3}-[0-9]{4}$";
}
