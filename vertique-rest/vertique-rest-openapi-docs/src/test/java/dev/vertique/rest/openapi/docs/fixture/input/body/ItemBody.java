// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.body;

/**
 * A plain request body type: one string member and no nested type, so the generator describes it as
 * an object schema without {@code $defs}, and that schema rejects {@code null}.
 */
public final class ItemBody {

    /** The item name. */
    public String name;
}
