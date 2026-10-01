// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The fixed form of {@link ReceiptZx}: the field {@code internalZx} carries both {@code @Hidden}
 * and {@code @Schema(hidden = true)}. The output generator leaves the member out of the published
 * schema, and its hidden-member report is empty. The member is still serialized in a response.
 */
public class FixedReceiptZx {

    /** The published total. */
    public int total;

    /** Hidden from the document by {@code @Schema(hidden = true)} on its own field. */
    @Hidden
    @Schema(hidden = true)
    public String internalZx;

    /** Creates an empty receipt. */
    public FixedReceiptZx() {}

    /**
     * Creates a receipt.
     *
     * @param total the total
     * @param internalZx the internal member
     */
    public FixedReceiptZx(int total, String internalZx) {
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
