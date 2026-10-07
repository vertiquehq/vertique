// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code fixed} at {@code /api} (mount {@code /api/*}), listing {@link
 * FixedReceiptResource}. Its one operation returns {@code Future<FixedReceiptZx>}, whose hidden
 * member carries {@code @Schema(hidden = true)} on its own field. Its document is public; its
 * {@code info} comes from configuration.
 */
@ApiDocs(policy = FixedApi.PublicDocsPolicy.class)
@RestApplication(name = FixedApi.NAME, path = FixedApi.PATH, resources = FixedReceiptResource.class)
public interface FixedApi {

    /** The application's name, which also names its document. */
    String NAME = "fixed";

    /** The application's path. */
    String PATH = "/api";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
