// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

/**
 * Corpus fixture for enum protection: the property's schema must keep its {@code enum} keyword, which
 * is the gate's only defence against an unknown enum string once a mapper falls back to
 * {@link CorpusStatus#UNKNOWN}. A profiled document that drops the keyword is a loosening delta.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class EnumDefaultValueDto {

    /** The enum property whose {@code enum} keyword the gate relies on. */
    public CorpusStatus status;
}
