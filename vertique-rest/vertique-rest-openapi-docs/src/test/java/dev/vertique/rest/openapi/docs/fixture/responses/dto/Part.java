// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output entity with the scalar members {@code partNumber} and {@code quantity}, the element
 * type of an array response.
 */
public class Part {

    /** The part number. */
    public String partNumber;

    /** The quantity. */
    public int quantity;

    /** Creates an empty part. */
    public Part() {}
}
