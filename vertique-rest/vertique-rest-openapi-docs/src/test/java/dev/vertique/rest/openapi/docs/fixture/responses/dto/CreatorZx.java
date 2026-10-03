// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An output DTO whose one property {@code code} is bound through a {@code @JsonCreator}
 * constructor parameter carrying {@code @Schema(hidden = true)}, and read through an unmarked
 * private field and public getter. The output generator ignores the marker on a creator parameter,
 * so it publishes the member; the output generator's hidden-member report holds {@code (this type,
 * "<init>#0", SCHEMA_HIDDEN, hideableBySchemaHidden = true)}.
 */
public class CreatorZx {

    private final String code;

    /**
     * Creates the DTO; the marker here is where the output generator does not read it.
     *
     * @param code the code
     */
    @JsonCreator
    public CreatorZx(@JsonProperty("code") @Schema(hidden = true) String code) {
        this.code = code;
    }

    /**
     * Returns the code.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }
}
