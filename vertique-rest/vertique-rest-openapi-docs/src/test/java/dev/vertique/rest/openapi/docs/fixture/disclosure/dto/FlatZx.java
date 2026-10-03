// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

/**
 * A flat request body: two published members, no nested type, no reserved name, no extras. Its
 * manifest is empty and its schema holds no {@code $defs} and no reference, so publishing it changes
 * nothing.
 */
public class FlatZx {

    /** A string member. */
    public String a;

    /** An integer member. */
    public int b;
}
