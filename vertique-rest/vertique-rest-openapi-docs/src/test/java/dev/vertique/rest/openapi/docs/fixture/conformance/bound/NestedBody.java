// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A case-insensitive holder with one hidden member, extra keys collected by a {@code @JsonAnySetter},
 * and one published member, {@code inner}, of type {@link ReservedBody}.
 */
@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
public class NestedBody {

    /** The published member holding a reserved-names body. */
    public ReservedBody inner;

    /** The hidden member whose name is reserved. */
    @Schema(hidden = true)
    public String secret;

    private final Map<String, Integer> extras = new LinkedHashMap<>();

    @JsonAnySetter
    private void putExtra(String key, Integer value) {
        extras.put(key, value);
    }
}
