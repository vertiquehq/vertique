// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

/**
 * A subclass of {@link Custom} with no producer of its own: the runtime finds {@code Custom}'s
 * producer by walking the superclass chain, so it is producer-bound too.
 */
public class SubCustom extends Custom {

    /** A member only the subclass declares. */
    public String extra;

    /** Creates an empty result. */
    public SubCustom() {}
}
