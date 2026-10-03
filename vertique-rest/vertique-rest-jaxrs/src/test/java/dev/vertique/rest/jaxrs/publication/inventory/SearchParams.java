// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite of {@link OrdersResource#createOrder}, bound without
 * {@code @Valid}, so Bean Validation never cascades into it.
 *
 * @param sort  query {@code sort}, unconstrained; carries a description for parity checks
 * @param page  query {@code page}, defaulting to {@code 1}
 * @param owner query {@code owner}, {@code @NotNull} but never cascaded into
 */
@RequestParams
public record SearchParams(
        @QueryParam("sort") @Parameter(description = "sort order")
        String sort,

        @QueryParam("page") @DefaultValue("1") int page,
        @QueryParam("owner") @NotNull String owner) {}
