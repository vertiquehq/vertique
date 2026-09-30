// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;

/**
 * The same declaration as {@link MgmtApi} with a public document: application {@code mgmt} at
 * {@code /api/mgmt} listing {@link ManagementResource}, whose declaring interface carries
 * {@code @ApiDocs(access = PUBLIC)}.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface DocumentedMgmtApi {}
