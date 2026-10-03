// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.HashMap;
import java.util.Map;

/**
 * A request body with a member {@link #audit} whose type {@link AuditTrailZx} is annotated
 * {@code @Hidden}; the hidden-member report's first entry is the type-level entry of {@link
 * AuditTrailZx} with marker {@code HIDDEN}.
 */
public class AccountHiddenTypeZx {

    /** A member whose type carries {@code @Hidden}. */
    public AuditTrailZx audit;

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
