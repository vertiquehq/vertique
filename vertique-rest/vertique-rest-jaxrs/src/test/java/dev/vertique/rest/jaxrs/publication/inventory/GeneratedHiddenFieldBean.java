// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite with one field marked {@code @Hidden} and one unmarked field.
 * It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 */
public class GeneratedHiddenFieldBean {

    /** Query {@code internal}, marked {@code @Hidden}. */
    @QueryParam("internal")
    @Hidden
    public String internal;

    /** Query {@code visible}, unmarked. */
    @QueryParam("visible")
    public String visible;
}
