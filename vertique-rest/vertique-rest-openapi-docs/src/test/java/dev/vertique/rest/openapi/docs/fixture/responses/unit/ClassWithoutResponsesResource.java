// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Thing;
import io.vertx.core.Future;

/**
 * A class declaring no response, the control beside the classes that do: its one method {@link
 * #d()} returns {@code Future<Thing>} and declares no response either. The method takes no
 * parameter and returns {@code null}; the class is never deployed.
 */
public class ClassWithoutResponsesResource {

    /** Creates the resource. */
    public ClassWithoutResponsesResource() {}

    /**
     * Returns {@code Future<Thing>}, declaring no response.
     *
     * @return {@code null}
     */
    public Future<Thing> d() {
        return null;
    }
}
