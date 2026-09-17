// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.util.Optional;

/**
 * Corpus fixture for an {@code Optional<String>} property: the mapper's property model decides
 * whether the wrapper is unwrapped to a plain string, so this document is where a mapper-model delta
 * between the legacy generator and a profiled one becomes visible.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class OptionalPropertyDto {

    /** The optional string property. */
    public Optional<String> note;
}
