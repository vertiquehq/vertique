// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid.crossmount;

import dev.vertique.rest.core.application.RestApplication;

/**
 * TP-009's two named registrations (T023 contract): {@code public} and {@code partner}, each
 * listing one unrelated resource whose sole method's default operationId is {@code "list"}. Each of
 * TP-009's three rows (strategy {@code web-validation}; strategy {@code openapi-contract} with
 * distinct contract locations; strategy {@code openapi-contract} with one shared global location)
 * registers both interfaces through its own dedicated module, so the effective
 * {@code openapiPath} differs per row without changing the declaring interfaces. Never implemented:
 * native composition reads each interface's registration directly.
 */
public final class CrossMountOperationIdApis {

    private CrossMountOperationIdApis() {}

    /** Lists only {@link PublicListResource}. */
    @RestApplication(name = "public", path = "/api/public", resources = PublicListResource.class)
    public interface PublicApi {}

    /** Lists only {@link PartnerListResource}. */
    @RestApplication(name = "partner", path = "/api/partner", resources = PartnerListResource.class)
    public interface PartnerApi {}
}
