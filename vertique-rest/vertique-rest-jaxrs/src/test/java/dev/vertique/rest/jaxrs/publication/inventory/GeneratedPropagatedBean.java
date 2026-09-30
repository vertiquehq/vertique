// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite whose fields carry no hiding marker; the method parameter that
 * binds it is marked {@code @Parameter(hidden = true)}. It is the generated-path twin of the same-named fixture without the {@code Generated} prefix; its hand-written {@code _BeanParamModel} companion makes the bean-param registry find a model for it.
 */
public class GeneratedPropagatedBean {

    /** Query {@code h1}, unmarked. */
    @QueryParam("h1")
    public String h1;

    /** Header {@code X-H2}, unmarked. */
    @HeaderParam("X-H2")
    public String h2;
}
