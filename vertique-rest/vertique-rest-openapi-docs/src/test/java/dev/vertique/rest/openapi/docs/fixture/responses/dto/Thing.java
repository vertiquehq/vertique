// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

/**
 * A flat output entity with the scalar members {@code id} and {@code name}. It reaches no other
 * type, so its output schema has no shared definitions and the published component equals its
 * generated output schema.
 */
public class Thing {

    /** The identifier. */
    public int id;

    /** The display name. */
    public String name;

    /** Creates an empty entity. */
    public Thing() {}
}
