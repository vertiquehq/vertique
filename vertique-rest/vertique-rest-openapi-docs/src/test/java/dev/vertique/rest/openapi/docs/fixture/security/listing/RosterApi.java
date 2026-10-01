// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.listing;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented public application {@code roster} at {@code /api/roster}, listing {@link
 * BetaResource}, {@link AlphaResource}, and {@link OpenResource} in that order. Three of its
 * operations require the scopeless scheme {@value RosterSchemeModule#SCHEME}; their operation ids
 * sort differently from their paths, and one path carries two methods. Registered by hand through
 * {@link RosterRegistrationModule}; the annotation processor never sees it.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(
        name = RosterApi.NAME,
        path = RosterApi.PATH,
        resources = {BetaResource.class, AlphaResource.class, OpenResource.class})
public interface RosterApi {

    /** The application's name, which also names its document. */
    String NAME = "roster";

    /** The application's path. */
    String PATH = "/api/roster";
}
