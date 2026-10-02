// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

/** The response body of {@link BackOfficeOrderResource}'s operations: two plain members. */
public class OrderReceipt {

    /** The order's id. */
    public String orderId;

    /** The order's status. */
    public String status;

    /** Creates an empty receipt, for deserialization. */
    public OrderReceipt() {}

    /**
     * Creates a receipt.
     *
     * @param orderId the order's id
     * @param status  the order's status
     */
    public OrderReceipt(String orderId, String status) {
        this.orderId = orderId;
        this.status = status;
    }
}
