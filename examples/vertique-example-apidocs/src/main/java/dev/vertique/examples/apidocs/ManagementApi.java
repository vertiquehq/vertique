// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dev.vertique.examples.apidocs.resource.ManagementResource;
import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/**
 * The management application, mounted at {@code /api/mgmt}.
 *
 * <p>{@link ApiDocs} with a policy that requires the {@code admin} role serves its OpenAPI document
 * only to callers the {@code bearerAuth} scheme authenticates and who hold that role. The access
 * policy is code: configuration can describe or disable the document but never change who may read
 * it. The interface declares no {@code @OpenAPIDefinition}, so the document's {@code info} comes
 * from the shipped configuration.
 */
@RestApplication(
        name = "management",
        path = "/api/mgmt",
        resources = {ManagementResource.class})
@ApiDocs(policy = ManagementApi.DocsPolicy.class, securityScheme = "bearerAuth")
public interface ManagementApi {

    /** Only readers holding the {@code admin} role may read the document. */
    @RolesAllowed("admin")
    public interface DocsPolicy extends AccessPolicy {}
}
