// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.sharedid;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code supplier} at {@code /api/supplier}, listing {@link DeliveryResource}. Its
 * document is public.
 */
@ApiDocs(policy = SupplierApi.PublicDocsPolicy.class)
@RestApplication(name = SupplierApi.NAME, path = SupplierApi.PATH, resources = DeliveryResource.class)
public interface SupplierApi {

    /** The application's name, which also names its document. */
    String NAME = "supplier";

    /** The application's path. */
    String PATH = "/api/supplier";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
