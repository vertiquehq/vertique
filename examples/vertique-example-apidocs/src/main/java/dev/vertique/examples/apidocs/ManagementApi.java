// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dev.vertique.examples.apidocs.resource.ManagementResource;
import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The management application, mounted at {@code /api/mgmt}.
 *
 * <p>{@link ApiDocs} with {@link ApiDocs.Access#PROTECTED} serves its OpenAPI document only to
 * callers the {@code bearerAuth} scheme authenticates and who hold the {@code admin} role. The access
 * policy is code: configuration can describe or disable the document but never change who may read
 * it. The interface declares no {@code @OpenAPIDefinition}, so the document's {@code info} comes
 * from the shipped configuration.
 */
@RestApplication(
        name = "management",
        path = "/api/mgmt",
        resources = {ManagementResource.class})
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = "bearerAuth",
        rolesAllowed = {"admin"})
public interface ManagementApi {}
