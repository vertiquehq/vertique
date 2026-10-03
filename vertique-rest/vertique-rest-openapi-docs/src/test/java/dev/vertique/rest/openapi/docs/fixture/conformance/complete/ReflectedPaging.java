// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @BeanParam} bean of {@link ReflectedEntryResource}; field for field, annotations included,
 * the twin of the other side's paging bean. It has no generated model, so the runtime walks its fields reflectively.
 */
public class ReflectedPaging {

    /** Query {@value CompleteEntries#PAGE}, defaulting to {@value CompleteEntries#PAGE_DEFAULT}, described. */
    @QueryParam(CompleteEntries.PAGE)
    @DefaultValue(CompleteEntries.PAGE_DEFAULT)
    @Parameter(description = CompleteEntries.PAGE_DESCRIPTION)
    public int page;

    /** Header {@value CompleteEntries#PAGE_SIZE_HEADER}, undescribed. */
    @HeaderParam(CompleteEntries.PAGE_SIZE_HEADER)
    public String pageSize;

    /** Creates an empty bean. */
    public ReflectedPaging() {}
}
