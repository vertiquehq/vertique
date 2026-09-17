// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.time.LocalDate;

/**
 * Corpus fixture for the commonest DTO shape of all: a private field Jackson fills through
 * reflection, reachable only through a getter. The property is named {@code due} because the
 * gate-versus-binder proof posts {@code {"due":19000}} to it and records that the built-in mappers
 * bind an epoch-day number to {@code 2022-01-08}; renaming the property invalidates that evidence.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class PrivateDatePropertyDto {

    private LocalDate due;

    /**
     * Returns the due date.
     *
     * @return the due date
     */
    public LocalDate getDue() {
        return due;
    }
}
