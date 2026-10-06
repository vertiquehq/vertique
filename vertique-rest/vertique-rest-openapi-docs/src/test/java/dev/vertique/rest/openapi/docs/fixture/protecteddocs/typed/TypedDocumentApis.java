// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.RequiresAction;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The documented applications of the typed document policy fixtures, one per supported policy shape.
 * Every application names the bearer scheme except the public one, lists one resource, and declares
 * the document's {@code info}. The documents are served under {@code /apidocs/<name>/openapi.json}
 * and {@code /apidocs/<name>/openapi.yaml}.
 *
 * <p>Each application's policy is a public interface nested in its owner. The {@link Scopes}
 * application's resource carries its own role requirement, so a caller may read the document and not
 * the resource, or the resource and not the document: the resource keeps its own protection.
 */
public final class TypedDocumentApis {

    /** The security scheme every restrictive document names. */
    public static final String SCHEME = "bearerAuth";

    /** The role the roles-bearing policies require. */
    public static final String ROLE = "admin";

    /** The scope the scope-bearing policies require. */
    public static final String SCOPE = "docs.read";

    /** The action the action-bearing policies require, registered by the fixture's action registry. */
    public static final String ACTION = "typed.docs.read";

    /** The body every fixture resource answers. */
    public static final String BODY = "ok";

    private TypedDocumentApis() {}

    /** A public document, served to every caller and naming no scheme. */
    @ApiDocs(policy = Open.OpenPolicy.class)
    @OpenAPIDefinition(info = @Info(title = "Typed open", version = "1.0"))
    @RestApplication(name = Open.NAME, path = Open.PATH, resources = Open.Items.class)
    public interface Open {

        /** The application's name, which also names its document. */
        String NAME = "open";

        /** The application's path. */
        String PATH = "/api/open";

        /** Anyone may read the document. */
        @PermitAll
        interface OpenPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readOpenItems")
            public String read() {
                return BODY;
            }
        }
    }

    /** A deny document: its reader authenticates, then is always denied. */
    @ApiDocs(policy = Deny.DenyPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed deny", version = "1.0"))
    @RestApplication(name = Deny.NAME, path = Deny.PATH, resources = Deny.Items.class)
    public interface Deny {

        /** The application's name, which also names its document. */
        String NAME = "deny";

        /** The application's path. */
        String PATH = "/api/deny";

        /** Nobody may read the document. */
        @DenyAll
        interface DenyPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readDenyItems")
            public String read() {
                return BODY;
            }
        }
    }

    /** A document readable by any authenticated caller. */
    @ApiDocs(policy = Authenticated.AuthenticatedPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed authenticated", version = "1.0"))
    @RestApplication(name = Authenticated.NAME, path = Authenticated.PATH, resources = Authenticated.Items.class)
    public interface Authenticated {

        /** The application's name, which also names its document. */
        String NAME = "authenticated";

        /** The application's path. */
        String PATH = "/api/authenticated";

        /** Any authenticated reader may read the document. */
        @Authorized
        interface AuthenticatedPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readAuthenticatedItems")
            public String read() {
                return BODY;
            }
        }
    }

    /** A document readable by callers holding {@link TypedDocumentApis#ROLE}. */
    @ApiDocs(policy = Roles.RolesPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed roles", version = "1.0"))
    @RestApplication(name = Roles.NAME, path = Roles.PATH, resources = Roles.Items.class)
    public interface Roles {

        /** The application's name, which also names its document. */
        String NAME = "roles";

        /** The application's path. */
        String PATH = "/api/roles";

        /** Readers holding the role may read the document. */
        @RolesAllowed(ROLE)
        interface RolesPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readRolesItems")
            public String read() {
                return BODY;
            }
        }
    }

    /**
     * A document readable by callers holding {@link TypedDocumentApis#SCOPE}. Its resource requires
     * the role instead, on its own.
     */
    @ApiDocs(policy = Scopes.ScopesPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed scopes", version = "1.0"))
    @RestApplication(name = Scopes.NAME, path = Scopes.PATH, resources = Scopes.Items.class)
    public interface Scopes {

        /** The application's name, which also names its document. */
        String NAME = "scopes";

        /** The application's path. */
        String PATH = "/api/scopes";

        /** The path of the resource under the application's path. */
        String ITEMS = "/items";

        /** Readers holding the scope may read the document. */
        @Authorized(scopes = SCOPE)
        interface ScopesPolicy extends AccessPolicy {}

        /** The application's resource, protected by its own role requirement. */
        @Path(ITEMS)
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readScopesItems")
            @SecurityRequirement(name = SCHEME)
            @RolesAllowed(ROLE)
            public String read() {
                return BODY;
            }
        }
    }

    /** A document readable by callers the application's authorizer permits {@link TypedDocumentApis#ACTION}. */
    @ApiDocs(policy = Action.ActionPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed action", version = "1.0"))
    @RestApplication(name = Action.NAME, path = Action.PATH, resources = Action.Items.class)
    public interface Action {

        /** The application's name, which also names its document. */
        String NAME = "action";

        /** The application's path. */
        String PATH = "/api/action";

        /** Readers the authorizer permits the action may read the document. */
        @RequiresAction(ACTION)
        interface ActionPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readActionItems")
            public String read() {
                return BODY;
            }
        }
    }

    /** A document readable by callers holding the role and the scope and permitted the action. */
    @ApiDocs(policy = Combined.CombinedPolicy.class, securityScheme = SCHEME)
    @OpenAPIDefinition(info = @Info(title = "Typed combined", version = "1.0"))
    @RestApplication(name = Combined.NAME, path = Combined.PATH, resources = Combined.Items.class)
    public interface Combined {

        /** The application's name, which also names its document. */
        String NAME = "combined";

        /** The application's path. */
        String PATH = "/api/combined";

        /** Readers holding the role and the scope and permitted the action may read the document. */
        @RolesAllowed(ROLE)
        @Authorized(scopes = SCOPE)
        @RequiresAction(ACTION)
        interface CombinedPolicy extends AccessPolicy {}

        /** The application's resource. */
        @Path("/items")
        final class Items {

            /**
             * Reads the items.
             *
             * @return the fixed body
             */
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            @Operation(operationId = "readCombinedItems")
            public String read() {
                return BODY;
            }
        }
    }
}
