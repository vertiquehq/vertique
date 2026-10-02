// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.config;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;

/**
 * Application {@code public} at {@code /api/public} listing {@link CatalogResource}, documented
 * publicly. It carries no {@code @OpenAPIDefinition} itself but extends {@link InfoParentApi},
 * which does. The annotation processor refuses this shape, so only a hand-written registration
 * declares it, and only in a view that never builds mounts.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = PublicApi.NAME, path = PublicApi.PATH, resources = CatalogResource.class)
public interface InheritedInfoApi extends InfoParentApi {}
