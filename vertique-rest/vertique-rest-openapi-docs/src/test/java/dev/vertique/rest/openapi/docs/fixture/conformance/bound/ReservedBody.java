// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A case-insensitive body with one published member, {@code label}, one hidden member (so its name is
 * reserved and the validation gate guards extra keys against it), and extra keys with integer values
 * collected by a {@code @JsonAnySetter}. {@code label} carries no pattern and no bounded format.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class ReservedBody {

    /** The one published member. */
    public String label;

    /** The hidden member whose name is reserved. */
    @Schema(hidden = true)
    public String secret;

    private final Map<String, Integer> extras = new LinkedHashMap<>();

    @JsonAnySetter
    private void putExtra(String key, Integer value) {
        extras.put(key, value);
    }
}
