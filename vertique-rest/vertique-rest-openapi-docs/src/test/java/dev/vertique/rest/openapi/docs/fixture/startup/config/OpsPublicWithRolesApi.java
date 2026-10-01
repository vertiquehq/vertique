// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsApi;
import dev.vertique.rest.openapi.docs.fixture.startup.OpsResource;

/**
 * Application {@code ops} at {@code /api/ops} listing {@link OpsResource}, whose public document lists allowed roles. The annotation processor refuses this shape; only a hand-written registration declares it.
 */
@ApiDocs(
        access = ApiDocs.Access.PUBLIC,
        rolesAllowed = {"admin"})
@RestApplication(name = OpsApi.NAME, path = OpsApi.PATH, resources = OpsResource.class)
public interface OpsPublicWithRolesApi {}
