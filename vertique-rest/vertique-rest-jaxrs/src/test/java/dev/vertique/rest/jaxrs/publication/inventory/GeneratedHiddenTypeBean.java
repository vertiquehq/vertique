// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite whose type is marked {@code @Hidden} and whose field carries no
 * marker; the method parameter that binds it is unannotated apart from {@code @BeanParam}. It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 */
@Hidden
public class GeneratedHiddenTypeBean {

    /** Query {@code h5}, unmarked. */
    @QueryParam("h5")
    public String h5;
}
