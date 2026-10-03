// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.core.request.RequestParams;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @RequestParams} record of {@link GeneratedSearchResource}, the twin of {@link
 * ReflectedSearchOptions}. Its companion {@link GeneratedSearchOptions_BeanParamModel} makes the
 * bean-param registry find a generated model for it.
 *
 * @param sort query {@code sort}, unconstrained
 */
@RequestParams
public record GeneratedSearchOptions(@QueryParam("sort") String sort) {}
