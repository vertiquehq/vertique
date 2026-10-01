// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.HashMap;
import java.util.Map;

/**
 * A request body with both a {@code @Hidden} field {@code backdoorZx} and a member {@link #audit} of
 * the {@code @Hidden} type {@link AuditTrailZx}. The hidden-member report orders entries by declaring
 * type, so this type's own {@code backdoorZx} entry comes first ({@code ...dto.AccountFormZx} sorts
 * before {@code ...dto.AuditTrailZx}).
 */
public class AccountFormZx {

    /** Marked {@code @Hidden} only, so the generator still describes it. */
    @Hidden
    public String backdoorZx;

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
