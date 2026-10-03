// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A JavaBean request body whose property {@code pinZx} has a private field, a getter carrying
 * {@code @Schema(hidden = true)}, and a setter. The input generator describes the property through
 * its setter and cannot leave it out; the hidden-member report's first entry is {@code (this type,
 * "getPinZx", SCHEMA_HIDDEN, hideableBySchemaHidden = false)}. Like the probed layout, it declares no
 * any-setter.
 */
public class AccountBeanZx {

    private String pinZx;

    private String name;

    /**
     * Returns the pin.
     *
     * @return the pin
     */
    @Schema(hidden = true)
    public String getPinZx() {
        return pinZx;
    }

    /**
     * Sets the pin; the input generator describes the property through this setter.
     *
     * @param pinZx the pin
     */
    public void setPinZx(String pinZx) {
        this.pinZx = pinZx;
    }

    /**
     * Returns the name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }
}
