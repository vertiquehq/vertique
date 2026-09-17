// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation.corpus;

import java.time.Instant;

/**
 * Corpus fixture for an {@code Instant} property: without java-time support the generator introspects
 * the type structurally, while a profile mapper that registers java-time support describes it as a
 * scalar. The two documents differ, which is the point of pinning both.
 *
 * <p>Frozen shape — this DTO is a subject of a byte-exact golden comparison. Adding, removing, or
 * renaming a property changes every corpus document that contains it.
 */
public class InstantPropertyDto {

    /** The instant property. */
    public Instant occurredAt;
}
