// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.body;

/**
 * A request body type holding a {@link RecursiveMap} as a property, so its generated schema carries a
 * root {@code $defs} entry {@code RecursiveMap} referenced as {@code #/$defs/RecursiveMap} both from
 * the property {@code tree} and from the entry itself.
 */
public final class TreeBody {

    /** The self-referential map. */
    public RecursiveMap tree;
}
