// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * A nested type whose schema carries a case-sensitive guard: one schema-hidden field, one published
 * field, and described extras, so its schema holds {@code propertyNames.not.enum [secretZx]}.
 * Parents that copy its schema ({@link AliasedGuardZx}, {@link FoldCopyGuardZx}) publish one guard
 * per copy, and the manifest lists each copy.
 */
public class GuardedChildZx {

    /** Hidden from the document; reserved by the guard. */
    @Schema(hidden = true)
    public String secretZx;

    /** The one published string member. */
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
