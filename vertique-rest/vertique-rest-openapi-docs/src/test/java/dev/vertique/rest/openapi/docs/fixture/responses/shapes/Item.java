// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

/**
 * A flat output entity with one string member {@code itemName}, the type argument {@link
 * ItemResource} binds for {@link CrudResource}'s type variable.
 */
public class Item {

    /** The name. */
    public String itemName;

    /** Creates an empty item. */
    public Item() {}
}
