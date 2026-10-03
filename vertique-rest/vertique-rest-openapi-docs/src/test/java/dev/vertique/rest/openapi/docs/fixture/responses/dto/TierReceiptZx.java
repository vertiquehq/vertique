// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * An output DTO whose member {@code tier} is the enum {@link TierZx}, one of whose constants
 * carries {@code @Schema(hidden = true)}.
 */
public class TierReceiptZx {

    /** A member of an enum type with a hidden constant. */
    public TierZx tier;

    /** Creates an empty receipt. */
    public TierReceiptZx() {}

    /**
     * Creates a receipt.
     *
     * @param tier the tier
     */
    public TierReceiptZx(TierZx tier) {
        this.tier = tier;
    }

    /**
     * Returns the tier.
     *
     * @return the tier
     */
    public TierZx getTier() {
        return tier;
    }
}
