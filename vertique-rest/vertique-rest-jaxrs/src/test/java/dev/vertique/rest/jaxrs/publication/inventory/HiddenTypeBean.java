// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite whose type is marked {@code @Hidden} and whose field carries no
 * marker; the method parameter that binds it is unannotated apart from {@code @BeanParam}. It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 */
@Hidden
public class HiddenTypeBean {

    /** Query {@code h5}, unmarked. */
    @QueryParam("h5")
    public String h5;
}
