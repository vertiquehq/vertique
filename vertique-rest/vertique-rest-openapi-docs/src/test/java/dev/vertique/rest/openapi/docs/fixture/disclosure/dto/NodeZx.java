// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A recursive request body: {@link #children} holds more nodes, so the generator's schema refers
 * back to its own root with the fragment-only reference {@code "$ref": "#"} (no {@code $defs}). One
 * schema-hidden field and described extras give the root a guard ({@code /propertyNames}) that the
 * manifest lists.
 */
public class NodeZx {

    /** A published string member. */
    public String name;

    /** The child nodes, described by a reference to the root schema. */
    public List<NodeZx> children;

    /** Hidden from the document; reserved by the root guard. */
    @Schema(hidden = true)
    public String secretZx;

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
