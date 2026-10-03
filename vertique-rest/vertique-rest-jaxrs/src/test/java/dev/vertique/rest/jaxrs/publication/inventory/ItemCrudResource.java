// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.Path;

/** Concrete resource binding {@link CrudBase}'s type variable to {@link Item}; declares no method. */
@Path("/items-crud")
public class ItemCrudResource extends CrudBase<Item> {

    @Override
    protected Item load() {
        return new Item("3", "three");
    }
}
