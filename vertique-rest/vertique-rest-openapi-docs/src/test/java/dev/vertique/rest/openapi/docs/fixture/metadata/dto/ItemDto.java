// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.dto;

/**
 * A plain JSON request body for the metadata tests: one string member and no nested type, so the
 * generator describes it as an object schema without {@code $defs}, and that schema rejects {@code
 * null}.
 */
public final class ItemDto {

    /** The item name. */
    public String name;
}
