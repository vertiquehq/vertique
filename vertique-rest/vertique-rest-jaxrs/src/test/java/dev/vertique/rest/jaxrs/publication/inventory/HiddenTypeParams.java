// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite whose type is marked {@code @Hidden} and whose component
 * carries no marker; the method parameter that binds it is unannotated. It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 *
 * @param h6 query {@code h6}, unmarked
 */
@Hidden
@RequestParams
public record HiddenTypeParams(@QueryParam("h6") String h6) {}
