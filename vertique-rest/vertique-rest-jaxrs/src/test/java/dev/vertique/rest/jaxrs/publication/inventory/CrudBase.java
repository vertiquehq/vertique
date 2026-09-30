// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Abstract generic superclass declaring a resource method whose return type is its own type
 * variable, so the generic return type stays unresolved until read against a concrete subclass.
 *
 * @param <T> the entity type
 */
public abstract class CrudBase<T> {

    /**
     * Finds the entity.
     *
     * @return the entity
     */
    @GET
    @Path("/crud")
    public T find() {
        return load();
    }

    /**
     * Supplies the entity {@link #find()} returns.
     *
     * @return the entity
     */
    protected abstract T load();
}
