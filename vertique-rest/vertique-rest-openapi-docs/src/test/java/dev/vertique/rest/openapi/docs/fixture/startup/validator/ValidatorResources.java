// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.validator;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * One small resource per hand-built JAX-RS mount of the composition-validator rows. Each declares
 * one {@code GET} operation whose method name, and so whose operation id, is distinct from every
 * other fixture operation; none declares a path parameter.
 */
public final class ValidatorResources {

    private ValidatorResources() {}

    /** The resource of the mount at {@code /apidocs/*}. */
    @Path("/at-prefix")
    public static final class AtPrefix {

        /**
         * Handles {@code GET /at-prefix}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readAtPrefix() {
            return "at-prefix";
        }
    }

    /** The resource of the mount at {@code /apidocs/admin/*}. */
    @Path("/under-prefix")
    public static final class UnderPrefix {

        /**
         * Handles {@code GET /under-prefix}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readUnderPrefix() {
            return "under-prefix";
        }
    }

    /** The resource of the mount at {@code /apidocsx/*}. */
    @Path("/prefix-without-boundary")
    public static final class PrefixWithoutBoundary {

        /**
         * Handles {@code GET /prefix-without-boundary}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readPrefixWithoutBoundary() {
            return "prefix-without-boundary";
        }
    }

    /** The resource of the mount at {@code /api/apidocs/*}. */
    @Path("/prefix-nested")
    public static final class PrefixNested {

        /**
         * Handles {@code GET /prefix-nested}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readPrefixNested() {
            return "prefix-nested";
        }
    }

    /** The resource of the mount at {@code /apidocs/*} beside a moved documentation prefix. */
    @Path("/moved-prefix")
    public static final class MovedPrefix {

        /**
         * Handles {@code GET /moved-prefix}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readMovedPrefix() {
            return "moved-prefix";
        }
    }

    /** The resource of the mount at {@code /:tenant/*}; it declares no {@code tenant} parameter. */
    @Path("/colon-tenant")
    public static final class ColonTenant {

        /**
         * Handles {@code GET /colon-tenant}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readColonTenant() {
            return "colon-tenant";
        }
    }

    /** The resource of the mount at <code>/{tenant}/*</code>; it declares no {@code tenant} parameter. */
    @Path("/brace-tenant")
    public static final class BraceTenant {

        /**
         * Handles {@code GET /brace-tenant}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readBraceTenant() {
            return "brace-tenant";
        }
    }

    /** The resource of the mount at <code>/api/{v}/*</code>; it declares no {@code v} parameter. */
    @Path("/versioned")
    public static final class Versioned {

        /**
         * Handles {@code GET /versioned}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readVersioned() {
            return "versioned";
        }
    }

    /** The resource of the mount at {@code /api/:tenant/*}; it declares no {@code tenant} parameter. */
    @Path("/api-tenant")
    public static final class ApiTenant {

        /**
         * Handles {@code GET /api-tenant}.
         *
         * @return a fixed marker
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String readApiTenant() {
            return "api-tenant";
        }
    }
}
