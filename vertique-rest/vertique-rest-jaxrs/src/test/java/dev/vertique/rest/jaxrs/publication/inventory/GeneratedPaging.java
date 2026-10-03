// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * Generated-path twin of {@link Paging}, with the same fields and annotations. Its companion
 * {@link GeneratedPaging_BeanParamModel} makes the bean-param registry find a model for it.
 */
public class GeneratedPaging {

    /** Query {@code pageSize}, defaulting to {@code 50}. */
    @QueryParam("pageSize")
    @DefaultValue("50")
    @NotNull
    public Integer pageSize;

    /** Query {@code filter}. */
    @QueryParam("filter")
    @NotBlank
    public String filter;
}
