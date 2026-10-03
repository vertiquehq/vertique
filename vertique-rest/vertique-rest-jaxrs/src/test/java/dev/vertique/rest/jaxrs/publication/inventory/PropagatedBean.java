// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite whose fields carry no hiding marker; the method parameter that
 * binds it is marked {@code @Parameter(hidden = true)}. It is a reflection-path fixture: no generated companion exists for it, and none may be added.
 */
public class PropagatedBean {

    /** Query {@code h1}, unmarked. */
    @QueryParam("h1")
    public String h1;

    /** Header {@code X-H2}, unmarked. */
    @HeaderParam("X-H2")
    public String h2;
}
