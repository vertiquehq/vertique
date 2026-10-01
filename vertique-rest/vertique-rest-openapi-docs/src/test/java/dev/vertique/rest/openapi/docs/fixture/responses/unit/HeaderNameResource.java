// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.core.Response;

/**
 * Header-name variants of one {@code 200} response: one method per case, assembled one at a time.
 * Header names are compared ignoring ASCII case within a status. Every method takes no parameter
 * and returns {@code null}; the class is never deployed.
 */
public class HeaderNameResource {

    /** Creates the resource. */
    public HeaderNameResource() {}

    /**
     * Declares {@code X-Rate} twice with identical spelling.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = {
                @Header(name = "X-Rate", schema = @Schema(implementation = Integer.class)),
                @Header(name = "X-Rate", schema = @Schema(implementation = Integer.class))
            })
    public Response exactDuplicateHeader() {
        return null;
    }

    /**
     * Declares {@code X-Rate} and {@code x-rate}, which differ only in ASCII case.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = {
                @Header(name = "X-Rate", schema = @Schema(implementation = Integer.class)),
                @Header(name = "x-rate", schema = @Schema(implementation = Integer.class))
            })
    public Response caseVariantHeader() {
        return null;
    }

    /**
     * Declares two different header names, {@code X-Rate} and {@code X-Limit}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = {
                @Header(name = "X-Rate", schema = @Schema(implementation = Integer.class)),
                @Header(name = "X-Limit", schema = @Schema(implementation = Integer.class))
            })
    public Response distinctHeaders() {
        return null;
    }
}
