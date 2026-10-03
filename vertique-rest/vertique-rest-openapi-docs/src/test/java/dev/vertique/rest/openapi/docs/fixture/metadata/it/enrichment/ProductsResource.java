// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ProductDto;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link ShopApi}: one operation taking a {@link ProductDto} body, whose JSON member
 * names differ from its Java member names and whose members carry {@code @Schema} documentation.
 */
@Path("/products")
public class ProductsResource {

    /** The operation id of {@link #createProduct}. */
    public static final String CREATE_PRODUCT = "createProduct";

    /** Creates the resource. */
    public ProductsResource() {}

    /**
     * Handles {@code POST /products}; answers with an empty response.
     *
     * @param product the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = CREATE_PRODUCT, summary = "Create product")
    public void createProduct(ProductDto product) {
        // Nothing to do: no test sends a request to this operation.
    }
}
