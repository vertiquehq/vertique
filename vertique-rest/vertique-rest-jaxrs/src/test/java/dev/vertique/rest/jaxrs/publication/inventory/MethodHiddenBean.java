// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import jakarta.ws.rs.QueryParam;

/**
 * {@code @BeanParam} POJO composite of two unmarked fields; the method binding it hides one of them
 * by a method-level {@code @Parameter}. It is a reflection-path fixture: no generated companion
 * exists for it, and none may be added.
 */
public class MethodHiddenBean {

    /** Query {@code internal}, unmarked; hidden by the binding method's {@code @Parameter}. */
    @QueryParam("internal")
    public String internal;

    /** Query {@code external}, unmarked and never hidden. */
    @QueryParam("external")
    public String external;
}
