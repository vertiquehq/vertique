// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dev.vertique.rest.core.request.RequestParams;
import jakarta.ws.rs.QueryParam;

/**
 * The {@code @RequestParams} record of {@link ReflectedSearchResource}, the twin of {@link
 * GeneratedSearchOptions}. It has no generated model, so the runtime walks its components
 * reflectively.
 *
 * @param sort query {@code sort}, unconstrained
 */
@RequestParams
public record ReflectedSearchOptions(@QueryParam("sort") String sort) {}
