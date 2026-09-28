// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy.app;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.jaxrs.application.policy.OrderMismatchResource;
import dev.vertique.rest.jaxrs.application.policy.PermitAllResource;
import dev.vertique.rest.jaxrs.application.policy.PermitAllScopedResource;
import dev.vertique.rest.jaxrs.application.policy.RequiresActionResource;
import dev.vertique.rest.jaxrs.application.policy.ScopelessRequirementResource;
import dev.vertique.rest.jaxrs.application.policy.UnannotatedResource;

/**
 * T005 TP-002/TP-004's application-mount declaring interfaces (T023 L22 port): one per
 * {@code policy} resource variant (a) to (f), in place of the removed
 * {@code jakarta.ws.rs.core.Application} subclass's config-selected shape. Each interface is
 * registered by exactly one
 * method of this package's {@link GeneratedJaxRsResourcesModule}, gated on the same
 * {@code policy.<variant>.enabled} property that already gates that variant's catalog entry (and,
 * off an application mount, its zero-declaration {@code @JaxRsResources} contribution, D001), so
 * exactly one variant's registration is active per test invocation, mounted at {@code /api/mgmt}.
 * Every interface must carry a distinct name (FR-022 applies to inactive registrations too), even
 * though only one is ever active at a time. Never implemented: native composition never
 * instantiates a declaring interface.
 */
public final class ManagementApis {

    private ManagementApis() {}

    /** (a) unannotated resource with two implicit operations. */
    @RestApplication(name = "mgmt-unannotated", path = "/api/mgmt", resources = UnannotatedResource.class)
    public interface UnannotatedApi {}

    /** (b) class-level {@code @PermitAll} resource. */
    @RestApplication(name = "mgmt-permit-all", path = "/api/mgmt", resources = PermitAllResource.class)
    public interface PermitAllApi {}

    /** (c) scopeless {@code @SecurityRequirement} resource. */
    @RestApplication(name = "mgmt-scopeless", path = "/api/mgmt", resources = ScopelessRequirementResource.class)
    public interface ScopelessApi {}

    /** (d) {@code @RequiresAction} resource. */
    @RestApplication(name = "mgmt-requires-action", path = "/api/mgmt", resources = RequiresActionResource.class)
    public interface RequiresActionApi {}

    /** (e) {@code @PermitAll} with a scoped {@code @SecurityRequirement}. */
    @RestApplication(name = "mgmt-permit-all-scoped", path = "/api/mgmt", resources = PermitAllScopedResource.class)
    public interface PermitAllScopedApi {}

    /** (f) (G2-10) unannotated resource whose registration order differs from full-path sort order. */
    @RestApplication(name = "mgmt-order-mismatch", path = "/api/mgmt", resources = OrderMismatchResource.class)
    public interface OrderMismatchApi {}
}
