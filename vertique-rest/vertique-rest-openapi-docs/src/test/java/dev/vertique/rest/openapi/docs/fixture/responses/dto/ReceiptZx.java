// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * An output DTO with a published {@code total} and a field {@code internalZx} that carries
 * {@code @Hidden} but not {@code @Schema(hidden = true)}. The output generator ignores
 * {@code @Hidden}, so it publishes the member; the output generator's hidden-member report holds
 * {@code (this type, "internalZx", HIDDEN, hideableBySchemaHidden = true)}.
 */
public class ReceiptZx {

    /** The published total. */
    public int total;

    /** Marked {@code @Hidden} only on its field, so the output generator still describes it. */
    @Hidden
    public String internalZx;

    /** Creates an empty receipt. */
    public ReceiptZx() {}

    /**
     * Creates a receipt.
     *
     * @param total the total
     * @param internalZx the internal member
     */
    public ReceiptZx(int total, String internalZx) {
        this.total = total;
        this.internalZx = internalZx;
    }

    /**
     * Returns the total.
     *
     * @return the total
     */
    public int getTotal() {
        return total;
    }

    /**
     * Returns the internal member.
     *
     * @return the internal member
     */
    public String getInternalZx() {
        return internalZx;
    }
}
