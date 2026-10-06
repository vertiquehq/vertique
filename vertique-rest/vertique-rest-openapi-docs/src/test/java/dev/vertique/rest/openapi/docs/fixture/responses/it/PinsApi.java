// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code pins} at {@code /api} (mount {@code /api/*}), listing {@link
 * PinResource}. Its one operation returns {@code Future<PinReceiptZx>}, whose setter carries
 * {@code @Schema(hidden = true)}. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(policy = PinsApi.PublicDocsPolicy.class)
@RestApplication(name = PinsApi.NAME, path = PinsApi.PATH, resources = PinResource.class)
public interface PinsApi {

    /** The application's name, which also names its document. */
    String NAME = "pins";

    /** The application's path. */
    String PATH = "/api";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
