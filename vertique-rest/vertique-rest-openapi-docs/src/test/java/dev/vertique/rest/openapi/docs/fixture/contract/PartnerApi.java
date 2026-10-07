// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@value #NAME} at {@value #PATH}, listing {@link PartnerOrderResource},
 * whose declaring interface names its own OpenAPI contract {@value #OPENAPI_PATH}, so its document is
 * that contract. It carries no {@code @OpenAPIDefinition}; no configuration gives it an {@code info}.
 */
@ApiDocs(policy = PartnerApi.PublicDocsPolicy.class)
@RestApplication(
        name = PartnerApi.NAME,
        path = PartnerApi.PATH,
        resources = PartnerOrderResource.class,
        openapiPath = PartnerApi.OPENAPI_PATH)
public interface PartnerApi {

    /** The application's name, which also names its document. */
    String NAME = "partner";

    /** The application's path. */
    String PATH = "/api/partner";

    /** The application's mount path, as its registration and publication report it. */
    String MOUNT_PATH = PATH + "/*";

    /** The application's own contract location, a test classpath resource. */
    String OPENAPI_PATH = ContractFiles.PARTNER;

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
