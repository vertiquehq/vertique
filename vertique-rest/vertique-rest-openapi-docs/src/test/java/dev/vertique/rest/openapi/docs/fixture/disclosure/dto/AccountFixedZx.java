// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashMap;
import java.util.Map;

/**
 * The fixed form of {@link AccountHiddenFieldZx}: {@code backdoorZx} carries both {@code @Hidden}
 * and {@code @Schema(hidden = true)} on its own field. The generator leaves the member out and,
 * because the type accepts extras, reserves its name in the root guard, which the manifest lists;
 * the hidden-member report is empty. The member still binds.
 */
public class AccountFixedZx {

    /** Hidden from the document by {@code @Schema(hidden = true)} on its own field. */
    @Hidden
    @Schema(hidden = true)
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
