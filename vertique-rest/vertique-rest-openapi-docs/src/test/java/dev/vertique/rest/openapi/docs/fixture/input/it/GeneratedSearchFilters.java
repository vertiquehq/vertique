// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @BeanParam} bean of {@link GeneratedSearchResource}, field for field the twin of {@link
 * ReflectedSearchFilters}. Its companion {@link GeneratedSearchFilters_BeanParamModel} makes the
 * bean-param registry find a generated model for it. Keep it in step with both.
 */
public class GeneratedSearchFilters {

    /** Query {@code q}: constrained by {@code @Size}, which nothing may publish for a composite field. */
    @QueryParam("q")
    @Size(max = TwinInputs.Q_MAX)
    @Parameter(description = TwinInputs.Q_DESCRIPTION)
    public String q;

    /** Header {@code X-Tenant}, unconstrained. */
    @HeaderParam(TwinInputs.TENANT_HEADER)
    public String tenant;

    /** Query {@code limit}, defaulting to {@code 10}. */
    @QueryParam("limit")
    @DefaultValue(TwinInputs.LIMIT_DEFAULT)
    public int limit;

    /** Creates an empty bean. */
    public GeneratedSearchFilters() {}
}
