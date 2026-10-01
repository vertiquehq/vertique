// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.enrichment;

import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.tags.Tags;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The catalog twin the reflective scanner describes: it has no generated companion. Declaration for
 * declaration, annotations included, it is {@link GeneratedCatalogResource}.
 */
@Path(CatalogOperations.RESOURCE_PATH)
@Tag(
        name = "catalog",
        description = "Catalog operations",
        externalDocs = @ExternalDocumentation(url = "https://docs.example.test/catalog"))
public class ReflectedCatalogResource implements CatalogOperations {

    /** Creates the resource. */
    public ReflectedCatalogResource() {}

    @Override
    @GET
    @Operation(
            operationId = LIST_ITEMS,
            summary = "List items",
            description = "All published items",
            tags = {"read"},
            deprecated = true,
            externalDocs = @ExternalDocumentation(url = "https://docs.example.test/list"))
    @Tag(name = "alpha", description = "First")
    public void listItems(@QueryParam("limit") @Parameter(example = "42", deprecated = true) String limit) {
        // Nothing to do: no test sends a request to this operation.
    }

    @Override
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = CREATE_ITEM)
    @Tags({@Tag(name = "zeta"), @Tag(name = "beta")})
    public void createItem(@RequestBody(description = "New item") ItemDto item) {
        // Nothing to do: no test sends a request to this operation.
    }
}
