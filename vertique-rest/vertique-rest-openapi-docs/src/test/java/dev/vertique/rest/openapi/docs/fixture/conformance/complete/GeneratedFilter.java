// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @RequestParams} record of {@link GeneratedEntryResource}; component for component,
 * annotations included, the twin of the other side's filter record. Its companion {@link GeneratedFilter_BeanParamModel} makes the bean-param registry find a generated model for it.
 *
 * @param sort   query {@value CompleteEntries#SORT}, described
 * @param locale cookie {@value CompleteEntries#LOCALE_COOKIE}, undescribed
 */
@RequestParams
public record GeneratedFilter(
        @QueryParam(CompleteEntries.SORT) @Parameter(description = CompleteEntries.SORT_DESCRIPTION)
        String sort,

        @CookieParam(CompleteEntries.LOCALE_COOKIE) String locale) {}
