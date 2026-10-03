// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.CatalogItem;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ItemView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Declared statuses with and without content on inferable and non-inferable returns. Each method is
 * named for its return shape and the status it declares:
 *
 * <ul>
 *   <li>{@link #entityDeclaring200And404()}: a plain {@code CatalogItem}, {@code 200} and {@code
 *       404} without content (the catalog item example);
 *   <li>{@link #futureOfListDeclaring2XX()}: {@code Future<List<CatalogItem>>}, the range {@code
 *       2XX};
 *   <li>{@link #stringDeclaring200()}: {@code String}, {@code 200} (assembled with produces {@code
 *       text/plain});
 *   <li>{@link #voidDeclaring200()}: {@code void}, {@code 200};
 *   <li>{@link #responseDeclaring200()}: {@code Response}, {@code 200};
 *   <li>{@link #entityDeclaring200WithContent()}: a plain {@code CatalogItem}, {@code 200} with
 *       {@code ItemView} content;
 *   <li>{@link #entityDeclaring204()}: a plain {@code CatalogItem}, {@code 204} without content.
 * </ul>
 *
 * <p>Every method takes no parameter and returns {@code null}; the class is never deployed.
 */
public class DeclaredSuccessResource {

    /** Creates the resource. */
    public DeclaredSuccessResource() {}

    /**
     * Returns a plain {@code CatalogItem}, declaring {@code 200} and {@code 404} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "200", description = "The catalog item")
    @ApiResponse(responseCode = "404", description = "Missing")
    public CatalogItem entityDeclaring200And404() {
        return null;
    }

    /**
     * Returns {@code Future<List<CatalogItem>>}, declaring {@code 2XX} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "2XX", description = "Some items")
    public Future<List<CatalogItem>> futureOfListDeclaring2XX() {
        return null;
    }

    /**
     * Returns {@code String}, declaring {@code 200} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "200", description = "Notes")
    public String stringDeclaring200() {
        return null;
    }

    /** Returns {@code void}, declaring {@code 200} without content. */
    @ApiResponse(responseCode = "200", description = "Deleted")
    public void voidDeclaring200() {
        // Nothing to return.
    }

    /**
     * Returns {@code Response}, declaring {@code 200} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "200", description = "Dynamic")
    public Response responseDeclaring200() {
        return null;
    }

    /**
     * Returns a plain {@code CatalogItem}, declaring {@code 200} with {@code ItemView} content.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "A view",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ItemView.class)))
    public CatalogItem entityDeclaring200WithContent() {
        return null;
    }

    /**
     * Returns a plain {@code CatalogItem}, declaring {@code 204} without content.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "204", description = "Touched")
    public CatalogItem entityDeclaring204() {
        return null;
    }
}
