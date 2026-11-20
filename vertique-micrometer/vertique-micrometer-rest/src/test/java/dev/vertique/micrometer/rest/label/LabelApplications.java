// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dev.vertique.rest.core.application.RestApplication;

/**
 * The three application declarations of the fixture. {@link PublicApi} and {@link PartnerApi} both
 * list {@link SharedItemsResource}, the same resource class under the same operationId and
 * mount-relative route, which the mount validator permits for one owner; {@link ManagementApi}
 * lists the role-protected {@link ManagementResource}. The interfaces are never implemented or
 * constructed: {@link LabelRegistrationModule} registers them the way the annotation processor
 * would.
 */
final class LabelApplications {

    private LabelApplications() {}

    /** Application {@code public} at {@code /api/public}, listing the shared resource. */
    @RestApplication(name = "public", path = "/api/public", resources = SharedItemsResource.class)
    interface PublicApi {}

    /** Application {@code partner} at {@code /api/partner}, listing the same shared resource. */
    @RestApplication(name = "partner", path = "/api/partner", resources = SharedItemsResource.class)
    interface PartnerApi {}

    /** Application {@code mgmt} at {@code /api/mgmt}, listing the role-protected resource. */
    @RestApplication(name = "mgmt", path = "/api/mgmt", resources = ManagementResource.class)
    interface ManagementApi {}
}
