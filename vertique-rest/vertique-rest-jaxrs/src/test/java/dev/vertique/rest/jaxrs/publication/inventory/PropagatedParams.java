// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.rest.core.request.RequestParams;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @RequestParams} record composite whose components carry no hiding marker; the method
 * parameter that binds it is marked {@code @Schema(hidden = true)}. It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 *
 * @param h3 query {@code h3}, unmarked
 * @param h4 query {@code h4}, unmarked
 */
@RequestParams
public record PropagatedParams(
        @QueryParam("h3") String h3, @QueryParam("h4") String h4) {}
