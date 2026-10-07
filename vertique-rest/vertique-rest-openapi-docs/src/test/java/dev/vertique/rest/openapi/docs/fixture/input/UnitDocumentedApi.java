// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The declaring interface of the synthetic application mounts unit tests build with {@link
 * Publications}. It carries {@code @ApiDocs(policy = PublicDocsPolicy.class)}, so it stands for an application with
 * a public document. No server deploys it, so it declares no {@code @RestApplication}.
 */
@ApiDocs(policy = UnitDocumentedApi.PublicDocsPolicy.class)
public interface UnitDocumentedApi {
    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
