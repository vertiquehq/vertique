// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.HashMap;
import java.util.Map;

/**
 * A request body with a member {@link #tier} of the enum {@link TierZx}, whose constant {@code
 * INTERNAL_ZX} carries {@code @Schema(hidden = true)}; the hidden-member report's first entry names
 * {@link TierZx} and {@code INTERNAL_ZX}.
 */
public class AccountTierZx {

    /** A member whose enum type has a constant marked hidden. */
    public TierZx tier;

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
