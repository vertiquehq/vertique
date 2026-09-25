// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

/**
 * TP-005 (T004) case (d) fixture: a concrete {@link OpidInheritedBaseResource} subclass with its
 * own {@code @Path}, inheriting {@link OpidInheritedBaseResource#list()} without overriding it.
 * Because this class adds its own class-level {@code @Path} annotation, it does not keep
 * {@link OpidInheritedBaseResource}'s resource surface ({@code sameSurface} fails), so this
 * operation's normalized owner class is {@code OpidInheritedFirstResource} itself, not the base —
 * a different owner from {@link OpidInheritedSecondResource}'s, even though both share the
 * inherited method's name and (empty) parameter types.
 */
@Path("/opid-inherited-first")
public class OpidInheritedFirstResource extends OpidInheritedBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidInheritedFirstResource() {}
}
