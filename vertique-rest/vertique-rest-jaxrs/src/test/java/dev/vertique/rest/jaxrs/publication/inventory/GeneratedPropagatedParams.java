// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite whose components carry no hiding marker; the method
 * parameter that binds it is marked {@code @Schema(hidden = true)}. It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 *
 * @param h3 query {@code h3}, unmarked
 * @param h4 query {@code h4}, unmarked
 */
@RequestParams
public record GeneratedPropagatedParams(
        @QueryParam("h3") String h3, @QueryParam("h4") String h4) {}
