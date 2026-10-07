// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The declarations of the startup combinations of document policy and security scheme. Every
 * declaration is the application {@value #NAME} at {@value #PATH} listing {@link Items}; they differ
 * only in the policy and the scheme their {@code @ApiDocs} states, so a startup test deploys one
 * declaration at a time. The document's {@code info} comes from configuration.
 */
public final class TypedStartupApis {

    /** The name of every declared application, which also names its document. */
    public static final String NAME = "management";

    /** The path of every declared application. */
    public static final String PATH = "/api/mgmt";

    /** The registered scheme. */
    public static final String SCHEME = "bearerAuth";

    /** A scheme no handler registers. */
    public static final String UNKNOWN_SCHEME = "nope";

    /** The body the resource answers. */
    public static final String BODY = "ok";

    private TypedStartupApis() {}

    /** The resource of every declared application. */
    @Path("/items")
    public static final class Items {

        /** Creates the resource. */
        public Items() {}

        /**
         * Reads the items.
         *
         * @return the fixed body
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "readStartupItems")
        public String read() {
            return BODY;
        }
    }

    /** A public policy and no scheme. */
    @ApiDocs(policy = PublicWithoutScheme.OpenPolicy.class)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface PublicWithoutScheme {

        /** Anyone may read the document. */
        @PermitAll
        interface OpenPolicy extends AccessPolicy {}
    }

    /** A public policy and a registered scheme. */
    @ApiDocs(policy = PublicWithScheme.OpenPolicy.class, securityScheme = SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface PublicWithScheme {

        /** Anyone may read the document. */
        @PermitAll
        interface OpenPolicy extends AccessPolicy {}
    }

    /** A public policy and a scheme no handler registers. */
    @ApiDocs(policy = PublicWithUnknownScheme.OpenPolicy.class, securityScheme = UNKNOWN_SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface PublicWithUnknownScheme {

        /** Anyone may read the document. */
        @PermitAll
        interface OpenPolicy extends AccessPolicy {}
    }

    /** A deny policy and no scheme. */
    @ApiDocs(policy = DenyWithoutScheme.DenyPolicy.class)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface DenyWithoutScheme {

        /** Nobody may read the document. */
        @DenyAll
        interface DenyPolicy extends AccessPolicy {}
    }

    /** A deny policy and a registered scheme. */
    @ApiDocs(policy = DenyWithScheme.DenyPolicy.class, securityScheme = SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface DenyWithScheme {

        /** Nobody may read the document. */
        @DenyAll
        interface DenyPolicy extends AccessPolicy {}
    }

    /** A deny policy and a scheme no handler registers. */
    @ApiDocs(policy = DenyWithUnknownScheme.DenyPolicy.class, securityScheme = UNKNOWN_SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface DenyWithUnknownScheme {

        /** Nobody may read the document. */
        @DenyAll
        interface DenyPolicy extends AccessPolicy {}
    }

    /** An authenticated-only policy and no scheme. */
    @ApiDocs(policy = AuthenticatedWithoutScheme.AuthenticatedPolicy.class)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface AuthenticatedWithoutScheme {

        /** Any authenticated reader may read the document. */
        @Authorized
        interface AuthenticatedPolicy extends AccessPolicy {}
    }

    /** An authenticated-only policy and a blank scheme. */
    @ApiDocs(policy = AuthenticatedWithBlankScheme.AuthenticatedPolicy.class, securityScheme = " ")
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface AuthenticatedWithBlankScheme {

        /** Any authenticated reader may read the document. */
        @Authorized
        interface AuthenticatedPolicy extends AccessPolicy {}
    }

    /** An authenticated-only policy and a registered scheme. */
    @ApiDocs(policy = AuthenticatedWithScheme.AuthenticatedPolicy.class, securityScheme = SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface AuthenticatedWithScheme {

        /** Any authenticated reader may read the document. */
        @Authorized
        interface AuthenticatedPolicy extends AccessPolicy {}
    }

    /** An authenticated-only policy and a scheme no handler registers. */
    @ApiDocs(policy = AuthenticatedWithUnknownScheme.AuthenticatedPolicy.class, securityScheme = UNKNOWN_SCHEME)
    @RestApplication(name = NAME, path = PATH, resources = Items.class)
    public interface AuthenticatedWithUnknownScheme {

        /** Any authenticated reader may read the document. */
        @Authorized
        interface AuthenticatedPolicy extends AccessPolicy {}
    }
}
