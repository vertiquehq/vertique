// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.HashMap;
import java.util.Map;

/**
 * A request body whose field {@code backdoorZx} carries {@code @Hidden} but not {@code @Schema(hidden
 * = true)}. The input generator ignores {@code @Hidden}, so it describes the member; the
 * hidden-member report's first entry is {@code (this type, "backdoorZx", HIDDEN,
 * hideableBySchemaHidden = true)}.
 */
public class AccountHiddenFieldZx {

    /** Marked {@code @Hidden} only, so the generator still describes it. */
    @Hidden
    public String backdoorZx;

    /** The one plain published member. */
    public String name;

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
