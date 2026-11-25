// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.parity;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code parity} at {@code /api}, listing the three resources of {@link
 * ParityResources}. Its document level requires {@value #BEARER_AUTH} with the scope {@value
 * #DOCUMENT_SCOPE}; each resource then replaces, inherits, or opts out of it at the class and method
 * level. Its document is public; its {@code info} comes from configuration.
 */
@OpenAPIDefinition(security = @SecurityRequirement(name = ParityApi.BEARER_AUTH, scopes = ParityApi.DOCUMENT_SCOPE))
@ApiDocs(policy = ParityApi.PublicDocsPolicy.class)
@RestApplication(
        name = ParityApi.NAME,
        path = ParityApi.PATH,
        resources = {
            ParityResources.DocumentLevel.class,
            ParityResources.ClassLevel.class,
            ParityResources.PublicClass.class
        })
public interface ParityApi {

    /** The application's name, which also names its document. */
    String NAME = "parity";

    /** The application's path. */
    String PATH = "/api";

    /** The scheme every requirement names. */
    String BEARER_AUTH = "bearerAuth";

    /** The scope the document level requires. */
    String DOCUMENT_SCOPE = "doc.read";

    /** The scope the class level of {@link ParityResources.ClassLevel} requires. */
    String CLASS_SCOPE = "class.read";

    /** The scope the method level requires wherever a method declares its own requirement. */
    String METHOD_SCOPE = "method.read";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
