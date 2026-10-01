// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * A case-insensitively bound request body (class-level {@code ACCEPT_CASE_INSENSITIVE_PROPERTIES})
 * with one schema-hidden field, one published field, and described extras.
 *
 * <p>The generator's root {@code propertyNames} is an {@code allOf}: element {@code 0} refuses every
 * non-ASCII name ({@code [^\x00-\x7F]}), element {@code 1} is the guard that refuses the hidden name
 * in any letter case, spelled as a case-fold run of the name. The manifest lists exactly one
 * pointer, {@code /propertyNames/allOf/1}. The hidden-member report of this type is empty: the
 * marker sits on the property's own field and the case-insensitivity is declared on the class.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class SecretFoldZx {

    /** Hidden from the document; reserved in every letter case. */
    @Schema(hidden = true)
    public String secretTokenZx;

    /** The one published string member. */
    public String label;

    private final Map<String, Object> extra = new HashMap<>();

    /**
     * Accepts any member not declared above.
     *
     * @param k the member name
     * @param v the member value
     */
    @JsonAnySetter
    public void any(String k, Object v) {
        extra.put(k, v);
    }
}
