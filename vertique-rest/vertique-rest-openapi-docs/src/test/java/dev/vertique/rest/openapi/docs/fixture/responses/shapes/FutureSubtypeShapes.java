// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

import io.vertx.core.Future;

/**
 * A resource-like class returning a subtype of {@code io.vertx.core.Future}, which the runtime
 * unwraps but the documentation must not treat as {@code Future} itself. The method takes no
 * parameter and returns {@code null}: only its generic return type is read. The class is never
 * deployed.
 */
public class FutureSubtypeShapes {

    /** Creates the resource. */
    public FutureSubtypeShapes() {}

    /**
     * A {@code Future} subtype.
     *
     * @param <T> the result type
     */
    public interface MyFuture<T> extends Future<T> {}

    /**
     * Returns {@code MyFuture<Dto>}.
     *
     * @return {@code null}
     */
    public MyFuture<Dto> futureSubtypeOfDto() {
        return null;
    }
}
