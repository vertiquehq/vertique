// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite of {@link OrdersResource#createOrder}: a defaulted query field
 * and a {@code @NotNull} header field whose bound name differs from its Java member name.
 */
public class Filters {

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
