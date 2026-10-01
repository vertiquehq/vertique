// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.BadView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.CreatorZx;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.GoodView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Note;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ReceiptZx;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;

/**
 * Output types published at the positions beside a content schema: a response header's schema and
 * an {@code @ArraySchema} element, plus an inferred type whose creator parameter carries a hiding
 * marker. One method per case, assembled one at a time.
 *
 * <p>{@link #badHeader()} declares {@link BadView}, which the {@code vertique-strict} profile's output
 * generator refuses, as a header schema; {@link #hiddenArrayElement()} declares {@link ReceiptZx},
 * whose field {@code internalZx} carries {@code @Hidden} only, as an array element; {@link
 * #renamedHeader()} declares {@link Note}, whose member {@code note} is described as {@code remark},
 * as a header schema; {@link #goodHeaderAndArray()} is the control declaring {@link GoodView} at both
 * positions; and {@link #creatorParameterMarker()} returns {@code Future<CreatorZx>}, whose creator
 * parameter carries {@code @Schema(hidden = true)}. Every method takes no parameter and returns
 * {@code null}; the class is never deployed.
 */
public class RefusalPositionResource {

    /** Creates the resource. */
    public RefusalPositionResource() {}

    /**
     * Declares {@code BadView} as the schema of header {@code X-Bad} on status {@code 200}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = @Header(name = "X-Bad", schema = @Schema(implementation = BadView.class)))
    public Response badHeader() {
        return null;
    }

    /**
     * Declares an array of {@code ReceiptZx} as the JSON content of status {@code 206}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "206",
            description = "Parts",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ReceiptZx.class))))
    public Response hiddenArrayElement() {
        return null;
    }

    /**
     * Declares {@code Note} as the schema of header {@code X-Note} on status {@code 200}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = @Header(name = "X-Note", schema = @Schema(implementation = Note.class)))
    public Response renamedHeader() {
        return null;
    }

    /**
     * Declares {@code GoodView} as the schema of header {@code X-Good} and an array of {@code GoodView}
     * as the JSON content of status {@code 200}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = @Header(name = "X-Good", schema = @Schema(implementation = GoodView.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = GoodView.class))))
    public Response goodHeaderAndArray() {
        return null;
    }

    /**
     * Returns {@code Future<CreatorZx>}, declaring no response.
     *
     * @return {@code null}
     */
    public Future<CreatorZx> creatorParameterMarker() {
        return null;
    }

    /**
     * Declares {@code ReceiptZx}, whose field {@code internalZx} carries {@code @Hidden} only, as the
     * schema of header {@code X-Receipt} on status {@code 200}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            headers = @Header(name = "X-Receipt", schema = @Schema(implementation = ReceiptZx.class)))
    public Response hiddenHeader() {
        return null;
    }

    /**
     * Declares an array of {@code Note}, whose member {@code note} is described as {@code remark}, as
     * the JSON content of status {@code 200}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Note.class))))
    public Response renamedArrayElement() {
        return null;
    }
}
