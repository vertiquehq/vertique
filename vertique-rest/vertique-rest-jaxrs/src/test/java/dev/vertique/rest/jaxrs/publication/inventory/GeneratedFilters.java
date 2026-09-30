// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * Generated-path twin of {@link Filters}, with the same fields and annotations. Its companion
 * {@link GeneratedFilters_BeanParamModel} makes the bean-param registry find a model for it.
 */
public class GeneratedFilters {

    /** Query {@code limit}, defaulting to {@code 20}; carries a description for parity checks. */
    @QueryParam("limit")
    @DefaultValue("20")
    @Parameter(description = "maximum number of orders")
    public Integer limit;

    /** Header {@code X-Tenant}, rejected when absent in the {@code Default} group. */
    @HeaderParam("X-Tenant")
    @NotNull
    public String tenant;
}
