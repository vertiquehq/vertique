// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite of {@link KindsResource#listKinds}: a defaulted
 * {@code @NotNull} field and a {@code @NotBlank} field.
 */
public class Paging {

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
