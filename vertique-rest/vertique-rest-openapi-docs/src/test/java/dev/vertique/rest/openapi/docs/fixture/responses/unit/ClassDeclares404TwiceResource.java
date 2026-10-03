// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Thing;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;

/**
 * A class declaring status {@code 404} twice at the class level, with different descriptions so the
 * two declarations are distinct annotation instances. Its one method {@link #c()} returns {@code
 * Future<Thing>} and declares no response of its own. The method takes no parameter and returns
 * {@code null}; the class is never deployed.
 */
@ApiResponse(responseCode = "404", description = "First missing")
@ApiResponse(responseCode = "404", description = "Second missing")
public class ClassDeclares404TwiceResource {

    /** Creates the resource. */
    public ClassDeclares404TwiceResource() {}

    /**
     * Returns {@code Future<Thing>}, declaring no response of its own.
     *
     * @return {@code null}
     */
    public Future<Thing> c() {
        return null;
    }
}
