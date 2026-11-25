// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.parity;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The resources of {@link ParityApi}. Each operation answers {@code GET} with a fixed plain-text body
 * and is named for the security level that decides it.
 */
public final class ParityResources {

    private ParityResources() {}

    /** A resource with no class-level requirement: the document level applies unless a method replaces it. */
    @Path("/doc")
    public static class DocumentLevel {

        /** Creates the resource. */
        public DocumentLevel() {}

        /**
         * Declares nothing, so it inherits the document level.
         *
         * @return a fixed body
         */
        @GET
        @Path("/inherits")
        @Produces(MediaType.TEXT_PLAIN)
        public String documentInherits() {
            return "ok";
        }

        /**
         * Replaces the document level with a scoped requirement of its own.
         *
         * @return a fixed body
         */
        @GET
        @Path("/method")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = ParityApi.BEARER_AUTH, scopes = ParityApi.METHOD_SCOPE)
        public String documentMethodReplaces() {
            return "ok";
        }

        /**
         * Replaces the document level with a scopeless requirement.
         *
         * @return a fixed body
         */
        @GET
        @Path("/scopeless")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = ParityApi.BEARER_AUTH)
        public String documentMethodScopeless() {
            return "ok";
        }

        /**
         * Opts out of the document level with the empty marker.
         *
         * @return a fixed body
         */
        @GET
        @Path("/open")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirements({})
        public String documentMethodMarker() {
            return "ok";
        }

        /**
         * Opts out of the document level with the empty marker and says so with {@code @PermitAll}.
         *
         * @return a fixed body
         */
        @GET
        @Path("/permit")
        @Produces(MediaType.TEXT_PLAIN)
        @PermitAll
        @SecurityRequirements({})
        public String documentPermitAllWithMarker() {
            return "ok";
        }
    }

    /** A resource whose class level replaces the document level. */
    @Path("/cls")
    @SecurityRequirement(name = ParityApi.BEARER_AUTH, scopes = ParityApi.CLASS_SCOPE)
    public static class ClassLevel {

        /** Creates the resource. */
        public ClassLevel() {}

        /**
         * Declares nothing, so it inherits the class level.
         *
         * @return a fixed body
         */
        @GET
        @Path("/inherits")
        @Produces(MediaType.TEXT_PLAIN)
        public String classInherits() {
            return "ok";
        }

        /**
         * Replaces the class level with a scoped requirement of its own.
         *
         * @return a fixed body
         */
        @GET
        @Path("/method")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = ParityApi.BEARER_AUTH, scopes = ParityApi.METHOD_SCOPE)
        public String classMethodReplaces() {
            return "ok";
        }

        /**
         * Carries the empty marker, which never removes the class level.
         *
         * @return a fixed body
         */
        @GET
        @Path("/marker")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirements({})
        public String classMethodMarker() {
            return "ok";
        }
    }

    /** A resource whose class carries the empty marker, so its operations are public unless they declare their own. */
    @Path("/pub")
    @SecurityRequirements({})
    public static class PublicClass {

        /** Creates the resource. */
        public PublicClass() {}

        /**
         * Declares nothing, so the class marker makes it public.
         *
         * @return a fixed body
         */
        @GET
        @Path("/plain")
        @Produces(MediaType.TEXT_PLAIN)
        public String publicClassPlain() {
            return "ok";
        }

        /**
         * Declares its own scoped requirement under the class marker.
         *
         * @return a fixed body
         */
        @GET
        @Path("/own")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = ParityApi.BEARER_AUTH, scopes = ParityApi.METHOD_SCOPE)
        public String publicClassOwn() {
            return "ok";
        }
    }
}
