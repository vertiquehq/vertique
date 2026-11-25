// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

/**
 * Operation-id conflict case (d) fixture: a second concrete {@link OpidInheritedBaseResource} subclass
 * with its own {@code @Path}, inheriting {@link OpidInheritedBaseResource#list()} without
 * overriding it — the counterpart to {@link OpidInheritedFirstResource}. Its own class-level
 * {@code @Path} annotation likewise defeats {@code sameSurface}, so this operation's normalized
 * owner class is {@code OpidInheritedSecondResource} itself.
 */
@Path("/opid-inherited-second")
public class OpidInheritedSecondResource extends OpidInheritedBaseResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public OpidInheritedSecondResource() {}
}
