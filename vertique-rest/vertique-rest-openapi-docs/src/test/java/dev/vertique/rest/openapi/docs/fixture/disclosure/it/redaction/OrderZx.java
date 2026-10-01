// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

/**
 * The request body of {@link OrdersResource}: one string and one integer member, no hidden or
 * ignored member and no extras, so its manifest is empty. A failure message about this body must
 * name neither member.
 */
public class OrderZx {

    /** The name of the string member. */
    public static final String ITEM_CODE = "itemCodeZx";

    /** The name of the integer member. */
    public static final String QUANTITY = "quantityZx";

    /** A string member. */
    public String itemCodeZx;

    /** An integer member; a string value for it fails the gate's type check. */
    public int quantityZx;
}
