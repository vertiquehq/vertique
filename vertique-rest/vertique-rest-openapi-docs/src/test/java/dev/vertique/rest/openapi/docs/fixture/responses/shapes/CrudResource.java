// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

import io.vertx.core.Future;
import java.util.List;

/**
 * A generic base resource whose methods return its class type variable {@code T}: a concrete
 * subclass such as {@link ItemResource} binds it, and a response registered on that subclass
 * resolves {@code T} against the subclass's generic superclass. The class is never deployed.
 *
 * @param <T> the entity type a subclass binds
 */
public abstract class CrudResource<T> {

    /** Creates the resource. */
    protected CrudResource() {}

    /**
     * Returns {@code T}.
     *
     * @return {@code null}
     */
    public T find() {
        return null;
    }

    /**
     * Returns {@code Future<List<T>>}.
     *
     * @return {@code null}
     */
    public Future<List<T>> list() {
        return null;
    }
}
