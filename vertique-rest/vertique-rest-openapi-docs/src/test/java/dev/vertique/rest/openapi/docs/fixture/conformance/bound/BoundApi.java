// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import dev.vertique.rest.core.application.RestApplication;

/**
 * The application {@code bound} at {@code /api/bound}, listing {@link BoundResource}. It declares no
 * documentation, so no document is published for it.
 */
@RestApplication(name = BoundApi.NAME, path = BoundApi.PATH, resources = BoundResource.class)
public interface BoundApi {

    /** The application's name. */
    String NAME = "bound";

    /** The application's path. */
    String PATH = "/api/bound";
}
