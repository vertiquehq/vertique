// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.complete;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @RequestParams} record of {@link ReflectedEntryResource}; component for component,
 * annotations included, the twin of the other side's filter record. It has no generated model, so the runtime walks its components reflectively.
 *
 * @param sort   query {@value CompleteEntries#SORT}, described
 * @param locale cookie {@value CompleteEntries#LOCALE_COOKIE}, undescribed
 */
@RequestParams
public record ReflectedFilter(
        @QueryParam(CompleteEntries.SORT) @Parameter(description = CompleteEntries.SORT_DESCRIPTION)
        String sort,

        @CookieParam(CompleteEntries.LOCALE_COOKIE) String locale) {}
