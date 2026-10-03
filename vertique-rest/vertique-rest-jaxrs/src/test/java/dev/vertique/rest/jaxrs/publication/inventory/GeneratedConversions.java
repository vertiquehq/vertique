// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.QueryParam;

/**
 * Generated-path twin of {@link Conversions}, with the same field and annotations. Its companion
 * {@link GeneratedConversions_BeanParamModel} makes the bean-param registry find a model for it.
 */
public class GeneratedConversions {

    /** Query {@code mode}. */
    @QueryParam("mode")
    @NotNull
    public String mode;
}
