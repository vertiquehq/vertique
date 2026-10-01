// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;

/**
 * The same declaration as {@link MgmtApi} with a protected document that names no security scheme:
 * application {@code mgmt} at {@code /api/mgmt} listing {@link ManagementResource}. The annotation
 * processor refuses this shape, so only a hand-written registration can carry it.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface NoSchemeApi {}
