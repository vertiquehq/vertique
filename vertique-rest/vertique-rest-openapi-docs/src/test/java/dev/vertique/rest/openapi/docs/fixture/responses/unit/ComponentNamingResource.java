// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Problem;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewA;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewB;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.core.Response;

/**
 * Declared responses whose explicit schemas are named per status.
 *
 * <p>{@link #getReport()} declares, on status {@code 200}, three contents with two different
 * implementations ({@code ViewA} for {@code application/json} and {@code application/vnd.a+json},
 * {@code ViewB} for {@code application/vnd.b+json}), and on status {@code 404} one implementation
 * ({@code Problem}) and a header {@code X-Rate} with an {@code Integer} schema. {@link
 * #getReportColon()} and {@link #getReportUnderscore()} each declare one {@code 200} content of
 * {@code ViewA}, for two operations whose ids sanitize to the same component name. Every method
 * takes no parameter and returns {@code null}; the class is never deployed.
 */
public class ComponentNamingResource {

    /** Creates the resource. */
    public ComponentNamingResource() {}

    /**
     * Returns {@code Response}, with two implementations on {@code 200} and one on {@code 404}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "Views",
            content = {
                @Content(mediaType = "application/json", schema = @Schema(implementation = ViewA.class)),
                @Content(mediaType = "application/vnd.b+json", schema = @Schema(implementation = ViewB.class)),
                @Content(mediaType = "application/vnd.a+json", schema = @Schema(implementation = ViewA.class))
            })
    @ApiResponse(
            responseCode = "404",
            description = "Missing",
            headers = @Header(name = "X-Rate", schema = @Schema(implementation = Integer.class)),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Problem.class)))
    public Response getReport() {
        return null;
    }

    /**
     * Returns {@code Response}, with one {@code 200} implementation; stands for operation {@code
     * get:report}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = ViewA.class)))
    public Response getReportColon() {
        return null;
    }

    /**
     * Returns {@code Response}, with one {@code 200} implementation; stands for operation {@code
     * get_report}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content = @Content(schema = @Schema(implementation = ViewA.class)))
    public Response getReportUnderscore() {
        return null;
    }
}
