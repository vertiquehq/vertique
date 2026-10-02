// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.core.application.RestApplication;

/**
 * The same declaration as {@link PublicApi} without {@code @ApiDocs}: application {@code public} at
 * {@code /api/public}, which has no document.
 */
@RestApplication(name = PublicApi.NAME, path = PublicApi.PATH, resources = CatalogResource.class)
public interface UndocumentedPublicApi {}
