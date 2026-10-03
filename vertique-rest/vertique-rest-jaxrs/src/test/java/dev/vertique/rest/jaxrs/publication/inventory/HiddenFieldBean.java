// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite with one field marked {@code @Hidden} and one unmarked field.
 * It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 */
public class HiddenFieldBean {

    /** Query {@code internal}, marked {@code @Hidden}. */
    @QueryParam("internal")
    @Hidden
    public String internal;

    /** Query {@code visible}, unmarked. */
    @QueryParam("visible")
    public String visible;
}
