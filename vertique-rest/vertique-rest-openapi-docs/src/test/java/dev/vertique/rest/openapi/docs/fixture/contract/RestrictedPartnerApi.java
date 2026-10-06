// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The application {@code partner} at {@value PartnerApi#PATH} with a public document, listing
 * {@link RestrictedPartnerOrderResource}, one of whose operations restricts its callers, and naming
 * its own contract {@value #OPENAPI_PATH}, which carries no {@code security} member anywhere.
 */
@ApiDocs(policy = RestrictedPartnerApi.PublicDocsPolicy.class)
@RestApplication(
        name = PartnerApi.NAME,
        path = PartnerApi.PATH,
        resources = RestrictedPartnerOrderResource.class,
        openapiPath = RestrictedPartnerApi.OPENAPI_PATH)
public interface RestrictedPartnerApi {

    /** The application's own contract location, a test classpath resource. */
    String OPENAPI_PATH = ContractFiles.PARTNER_RESTRICTED;

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
