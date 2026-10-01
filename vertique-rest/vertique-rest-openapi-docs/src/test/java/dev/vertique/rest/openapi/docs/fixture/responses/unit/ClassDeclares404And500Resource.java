// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Thing;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;

/**
 * A class declaring {@code 404} ({@code Class missing}) and {@code 500} ({@code Class failure}).
 *
 * <p>{@link #a()} declares {@code 404} ({@code Method missing}) and {@code 200} ({@code Method
 * OK}), the method's {@code 404} sharing a status with the class; {@link #b()} declares no response
 * of its own. Both return {@code Future<Thing>}, an inferable return. Every method takes no
 * parameter and returns {@code null}; the class is never deployed.
 */
@ApiResponse(responseCode = "404", description = "Class missing")
@ApiResponse(responseCode = "500", description = "Class failure")
public class ClassDeclares404And500Resource {

    /** Creates the resource. */
    public ClassDeclares404And500Resource() {}

    /**
     * Returns {@code Future<Thing>}, declaring {@code 404} and {@code 200} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "404", description = "Method missing")
    @ApiResponse(responseCode = "200", description = "Method OK")
    public Future<Thing> a() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>}, declaring no response of its own.
     *
     * @return {@code null}
     */
    public Future<Thing> b() {
        return null;
    }
}
