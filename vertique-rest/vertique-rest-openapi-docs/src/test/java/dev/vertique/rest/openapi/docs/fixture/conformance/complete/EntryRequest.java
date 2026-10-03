// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * The request body of the entry creation, shared by both twins: bound case-insensitively (class-level
 * {@code ACCEPT_CASE_INSENSITIVE_PROPERTIES} only), with one schema-hidden field, two published
 * fields, and extra members a {@code @JsonAnySetter} accepts.
 *
 * <p>The canonical generator refuses the hidden name in any letter case under the root {@code
 * propertyNames} and records that guard in the schema's redaction manifest, so the published body
 * component holds neither the guard nor the hidden name, while the non-ASCII refusal of the
 * case-insensitive binding stays.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class EntryRequest {

    /** Hidden from the document; reserved in every letter case. */
    @Schema(hidden = true)
    public String ownerToken;

    /** The entry title, published with its description. */
    @Schema(description = "The entry title")
    public String title;

    /** The entry text, published. */
    public String text;

    private final Map<String, Object> extra = new HashMap<>();

    /** Creates an empty request. */
    public EntryRequest() {}

    /**
     * Accepts any member not declared above.
     *
     * @param name  the member name
     * @param value the member value
     */
    @JsonAnySetter
    public void any(String name, Object value) {
        extra.put(name, value);
    }
}
