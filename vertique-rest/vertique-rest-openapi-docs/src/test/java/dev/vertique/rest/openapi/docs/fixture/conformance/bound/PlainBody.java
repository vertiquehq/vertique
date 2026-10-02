// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A case-insensitive body with one published member, {@code label}, and extra keys with integer values
 * collected by a {@code @JsonAnySetter}. It has no hidden member, so no name is reserved.
 * {@code label} carries no pattern and no bounded format.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class PlainBody {

    /** The one published member. */
    public String label;

    private final Map<String, Integer> extras = new LinkedHashMap<>();

    @JsonAnySetter
    private void putExtra(String key, Integer value) {
        extras.put(key, value);
    }
}
