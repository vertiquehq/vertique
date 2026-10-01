// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

/**
 * A generic resource class registered as itself: nothing binds its type variable {@code T}, so the
 * return type of {@link #get()} still holds a type variable after resolution against this class.
 * The class is never deployed.
 *
 * @param <T> a type variable no subclass binds
 */
public class GenericResource<T> {

    /** Creates the resource. */
    public GenericResource() {}

    /**
     * Returns {@code T}.
     *
     * @return {@code null}
     */
    public T get() {
        return null;
    }
}
