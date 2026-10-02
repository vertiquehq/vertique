// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resources of the caching fixture applications, one per application so that every document has
 * an operation to publish. Each answers {@code GET} with a fixed plain-text body and declares no
 * security requirement; the documents' protection comes from their applications' {@code @ApiDocs}
 * alone.
 */
public final class CachingResources {

    /** The fixed body every resource returns. */
    public static final String BODY = "ok";

    private CachingResources() {}

    /** The resource of {@link CachingPublicApi}: {@code GET /items}. */
    @Path("/items")
    public static final class Catalog {

        /** Creates the resource. */
        public Catalog() {}

        /**
         * Handles {@code GET /items}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String listItems() {
            return BODY;
        }
    }

    /** The resource of {@link CachingManagementApi}: {@code GET /status}. */
    @Path("/status")
    public static final class Management {

        /** Creates the resource. */
        public Management() {}

        /**
         * Handles {@code GET /status}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readStatus() {
            return BODY;
        }
    }

    /** The resource of {@link UndescribedKindApi}: {@code GET /undescribed}. */
    @Path("/undescribed")
    public static final class Undescribed {

        /** Creates the resource. */
        public Undescribed() {}

        /**
         * Handles {@code GET /undescribed}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readUndescribed() {
            return BODY;
        }
    }

    /** The resource of {@link HeaderKeyKindApi}: {@code GET /header-key}. */
    @Path("/header-key")
    public static final class HeaderKey {

        /** Creates the resource. */
        public HeaderKey() {}

        /**
         * Handles {@code GET /header-key}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readHeaderKey() {
            return BODY;
        }
    }

    /** The resource of {@link CookieKeyKindApi}: {@code GET /cookie-key}. */
    @Path("/cookie-key")
    public static final class CookieKey {

        /** Creates the resource. */
        public CookieKey() {}

        /**
         * Handles {@code GET /cookie-key}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readCookieKey() {
            return BODY;
        }
    }

    /** The resource of {@link QueryKeyKindApi}: {@code GET /query-key}. */
    @Path("/query-key")
    public static final class QueryKey {

        /** Creates the resource. */
        public QueryKey() {}

        /**
         * Handles {@code GET /query-key}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readQueryKey() {
            return BODY;
        }
    }

    /** The resource of {@link OAuthKindApi}: {@code GET /oauth}. */
    @Path("/oauth")
    public static final class OAuth {

        /** Creates the resource. */
        public OAuth() {}

        /**
         * Handles {@code GET /oauth}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readOAuth() {
            return BODY;
        }
    }

    /** The resource of {@link OpenIdKindApi}: {@code GET /openid}. */
    @Path("/openid")
    public static final class OpenId {

        /** Creates the resource. */
        public OpenId() {}

        /**
         * Handles {@code GET /openid}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readOpenId() {
            return BODY;
        }
    }

    /** The resource of {@link MutualTlsKindApi}: {@code GET /mutual-tls}. */
    @Path("/mutual-tls")
    public static final class MutualTls {

        /** Creates the resource. */
        public MutualTls() {}

        /**
         * Handles {@code GET /mutual-tls}.
         *
         * @return the fixed body {@value CachingResources#BODY}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readMutualTls() {
            return BODY;
        }
    }
}
