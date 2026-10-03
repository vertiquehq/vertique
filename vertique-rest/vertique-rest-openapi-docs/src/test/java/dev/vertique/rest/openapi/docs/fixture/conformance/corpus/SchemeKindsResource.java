// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.corpus;

import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingSchemes;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of the security scheme kinds application: one {@code GET} operation per described
 * scheme kind, each requiring exactly that scheme, so the document references, and publishes a
 * Security Scheme Object of, every kind: HTTP bearer ({@value CorpusDocuments#BEARER_AUTH}, the JWT
 * handler), an API key in a header, a cookie, and the query, OAuth 2, OpenID Connect, and mutual TLS
 * (the handlers of {@link CachingSchemes}). The undescribed scheme is not referenced: a referenced
 * scheme whose handler describes nothing fails the document by design. No requirement lists scopes.
 * The operation ids are the method names. No test sends a request to these operations.
 */
@Path("/kinds")
public class SchemeKindsResource {

    /** Creates the resource. */
    public SchemeKindsResource() {}

    /**
     * Requires the HTTP bearer scheme.
     *
     * @return a fixed text
     */
    @GET
    @Path("/bearer")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CorpusDocuments.BEARER_AUTH)
    public String readWithBearer() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the API key sent in a header.
     *
     * @return a fixed text
     */
    @GET
    @Path("/header-key")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.HEADER_KEY)
    public String readWithHeaderKey() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the API key sent in a cookie.
     *
     * @return a fixed text
     */
    @GET
    @Path("/cookie-key")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.COOKIE_KEY)
    public String readWithCookieKey() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the API key sent in the query.
     *
     * @return a fixed text
     */
    @GET
    @Path("/query-key")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.QUERY_KEY)
    public String readWithQueryKey() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the OAuth 2 scheme.
     *
     * @return a fixed text
     */
    @GET
    @Path("/oauth")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.OAUTH2)
    public String readWithOAuth() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the OpenID Connect scheme.
     *
     * @return a fixed text
     */
    @GET
    @Path("/openid")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.OPEN_ID_CONNECT)
    public String readWithOpenId() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }

    /**
     * Requires the mutual TLS scheme.
     *
     * @return a fixed text
     */
    @GET
    @Path("/mutual-tls")
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = CachingSchemes.MUTUAL_TLS)
    public String readWithMutualTls() {
        return CachingSchemes.FIXTURE_SUBJECT;
    }
}
