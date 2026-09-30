// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite whose type is marked {@code @Hidden} and whose component
 * carries no marker; the method parameter that binds it is unannotated. It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 *
 * @param h6 query {@code h6}, unmarked
 */
@Hidden
@RequestParams
public record GeneratedHiddenTypeParams(@QueryParam("h6") String h6) {}
