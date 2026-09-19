// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.time.LocalDate;

/**
 * Corpus fixture for a {@code LocalDate} property. The property is named {@code due} because the
 * binder-behaviour proofs post {@code {"due":123}} to it and record that the built-in mappers bind an
 * epoch-day number to {@code 1970-05-04}; renaming the property invalidates that recorded evidence.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class LocalDatePropertyDto {

    /** The local-date property the binder proofs post an epoch-day number to. */
    public LocalDate due;
}
