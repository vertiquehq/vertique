// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A request body bound through a {@code @JsonCreator} constructor whose first parameter {@code
 * tokenZx} carries {@code @Schema(hidden = true)} while its backing field and getter do not. The
 * input generator ignores the marker where it is declared; the hidden-member report's first entry is
 * {@code (this type, "<init>#0", SCHEMA_HIDDEN, hideableBySchemaHidden = true)}. Like the probed
 * layout, it declares no any-setter.
 */
public class AccountCtorZx {

    private final String tokenZx;

    private final String name;

    /**
     * Creates the body from its two properties.
     *
     * @param tokenZx the token; the hiding marker sits on this parameter only
     * @param name    the name
     */
    @JsonCreator
    public AccountCtorZx(
            @JsonProperty("tokenZx") @Schema(hidden = true) String tokenZx, @JsonProperty("name") String name) {
        this.tokenZx = tokenZx;
        this.name = name;
    }

    /**
     * Returns the token.
     *
     * @return the token
     */
    public String getTokenZx() {
        return tokenZx;
    }

    /**
     * Returns the name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }
}
