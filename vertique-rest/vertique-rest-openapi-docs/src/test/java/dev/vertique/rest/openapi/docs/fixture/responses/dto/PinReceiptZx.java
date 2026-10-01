// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An output DTO with a private field {@code pinZx}, a getter {@code getPinZx}, and a setter {@code
 * setPinZx} that carries {@code @Schema(hidden = true)}. The output generator ignores the marker on
 * a setter, so it publishes the member; the output generator's hidden-member report holds {@code
 * (this type, "setPinZx", SCHEMA_HIDDEN, hideableBySchemaHidden = true)}.
 */
public class PinReceiptZx {

    private String pinZx;

    /** Creates an empty receipt. */
    public PinReceiptZx() {}

    /**
     * Returns the pin.
     *
     * @return the pin
     */
    public String getPinZx() {
        return pinZx;
    }

    /**
     * Sets the pin; the marker here is where the output generator does not read it.
     *
     * @param pinZx the pin
     */
    @Schema(hidden = true)
    public void setPinZx(String pinZx) {
        this.pinZx = pinZx;
    }
}
