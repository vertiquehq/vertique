// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

/**
 * A request body whose captured schema carries a root {@code $defs} entry: its one member is the
 * self-referential {@link Branches}, which the generator describes as a local definition referenced
 * from the member and from itself.
 */
public final class GroveRequest {

    /** The self-referential map member. */
    public Branches tree;

    /** Creates an empty request. */
    public GroveRequest() {}
}
