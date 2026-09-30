// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Reflection-path resource whose one operation declares a query parameter for every requiredness
 * case that depends on defaults, collections, arrays, and the kind of constraint declared.
 */
@Path("/kinds")
public class KindsResource {

    /**
     * {@code GET /kinds}, declaring no validation groups.
     *
     * @param size   defaulted and {@code @NotNull}
     * @param tags   a non-array collection with only {@code @NotNull}
     * @param ids    a non-array collection with {@code @NotEmpty}
     * @param codes  an array with {@code @NotNull}
     * @param name   {@code @NotBlank}
     * @param title  {@code @NotEmpty}
     * @param both   {@code @NotNull} and {@code @Size}
     * @param note   {@code @Size} only
     * @param zip    the composed {@link ZipCode} only
     * @param plain  no constraint, only a documentation annotation
     * @param paging {@code @Valid} bean-param composite
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listKinds(
            @QueryParam("size") @DefaultValue("5") @NotNull Integer size,
            @QueryParam("tags") @NotNull List<String> tags,
            @QueryParam("ids") @NotEmpty List<String> ids,
            @QueryParam("codes") @NotNull String[] codes,
            @QueryParam("name") @NotBlank String name,
            @QueryParam("title") @NotEmpty String title,
            @QueryParam("both") @NotNull @Size(max = 10) String both,
            @QueryParam("note") @Size(min = 1) String note,
            @QueryParam("zip") @ZipCode String zip,
            @QueryParam("plain") @Parameter(description = "no constraint") String plain,
            @BeanParam @Valid Paging paging) {
        return "kinds";
    }
}
