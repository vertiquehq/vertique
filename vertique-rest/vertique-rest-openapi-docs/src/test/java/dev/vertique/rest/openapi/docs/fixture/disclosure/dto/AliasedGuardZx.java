// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * A request body whose member {@link #child} has two aliases, so the generator publishes the guarded
 * child schema once per accepted name ({@code child}, {@code a1}, {@code a2}): three guard copies,
 * each listed in the manifest. The type itself accepts no extras and has no guard of its own.
 */
public class AliasedGuardZx {

    /** The guarded child, also accepted as {@code a1} and {@code a2}. */
    @JsonAlias({"a1", "a2"})
    public GuardedChildZx child;

    /** A plain published string member. */
    public String other;
}
