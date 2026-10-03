// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.BadView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.GoodView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;

/**
 * Responses whose output types the {@code vertique-strict} profile's output generator refuses,
 * beside a control it generates; assembled under that profile.
 *
 * <p>{@link #getBad()} returns {@code Future<BadView>} without a declared response, so {@code
 * BadView} is the inferred output type; {@link #getBadExplicit()} returns {@code Response} and
 * declares {@code BadView} as the content of status {@code 409}; {@link #getGood()} returns {@code
 * Future<GoodView>}. {@link BadView} states why the generator refuses it. Every method takes no
 * parameter and returns {@code null}; the class is never deployed.
 */
public class GeneratorFailureResource {

    /** Creates the resource. */
    public GeneratorFailureResource() {}

    /**
     * Returns {@code Future<BadView>}, declaring no response.
     *
     * @return {@code null}
     */
    public Future<BadView> getBad() {
        return null;
    }

    /**
     * Returns {@code Response}, declaring {@code BadView} as the content of status {@code 409}.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "409", content = @Content(schema = @Schema(implementation = BadView.class)))
    public Response getBadExplicit() {
        return null;
    }

    /**
     * Returns {@code Future<GoodView>}, declaring no response.
     *
     * @return {@code null}
     */
    public Future<GoodView> getGood() {
        return null;
    }
}
