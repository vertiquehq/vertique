// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

/**
 * Corpus fixture for a property literally named {@code pattern}. In the produced document
 * {@code properties.pattern} is an <em>object</em> (that property's schema), not a regex string, so
 * the gate's regex walk must descend past it and never attempt to compile it.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class PatternNamedPropertyDto {

    /** A property whose name collides with the JSON Schema {@code pattern} keyword. */
    public String pattern;
}
