// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

/**
 * A concrete resource that binds {@link CrudResource}'s {@code T} to {@link Item} and declares no
 * method of its own: its inherited {@code T find()} resolves to {@code Item} and {@code
 * Future<List<T>> list()} to {@code Future<List<Item>>}. The class is never deployed.
 */
public class ItemResource extends CrudResource<Item> {

    /** Creates the resource. */
    public ItemResource() {}
}
