// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * The enum used by {@link EnumDefaultValueDto}. {@link #UNKNOWN} carries
 * {@code @JsonEnumDefaultValue}, so a mapper configured to read unknown enum values using the default
 * would bind an unrecognized string instead of rejecting it — which is exactly what the gate's
 * {@code enum} keyword must keep refusing.
 *
 * <p>Frozen shape — the constants and their order are part of the pinned document.
 */
public enum CorpusStatus {

    /** An ordinary constant. */
    ACTIVE,

    /** A second ordinary constant. */
    ARCHIVED,

    /** The Jackson fallback constant for an unrecognized input string. */
    @JsonEnumDefaultValue
    UNKNOWN
}
