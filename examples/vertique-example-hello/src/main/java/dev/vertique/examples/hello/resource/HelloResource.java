// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello.resource;

import dev.vertique.examples.hello.HelloConfig;
import dev.vertique.rest.core.security.Authorized;
import dev.vertique.security.SecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;

/**
 * Hello service resource demonstrating JAX-RS endpoints with authentication and authorization.
 *
 * <p>Class-level {@code @PermitAll} makes all endpoints public by default. Individual
 * methods can override this with {@code @RolesAllowed}, {@code @Authorized}, or {@code @DenyAll}.
 *
 * <p>Secured endpoints demonstrate:
 * <ul>
 *   <li>Role-based access control using {@code @RolesAllowed}</li>
 *   <li>Scope-based access control using {@code @Authorized(scopes = ...)}</li>
 *   <li>Combined role and scope requirements</li>
 *   <li>Security context injection (both framework and JAX-RS)</li>
 *   <li>Unconditional denial using {@code @DenyAll}</li>
 * </ul>
 */
@Slf4j
@Path("/hello")
@Tag(name = "Hello", description = "Hello service endpoints")
@PermitAll // Class-level default: all endpoints are open unless overridden
public class HelloResource {

    private final HelloConfig config;

    @Inject
    public HelloResource(HelloConfig config) {
        this.config = config;
    }

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "greet",
            summary = "Greet a person by name",
            description = "Returns a greeting message for the specified person")
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    public Future<GreetingResponse> greet(
            @Parameter(description = "The name to greet", required = true) @PathParam("name") String name) {
        log.info("Greeting: {}", name);
        String message = String.format(config.hello(), name);
        return Future.succeededFuture(new GreetingResponse(message));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "greetDefault", summary = "Default greeting", description = "Returns a greeting for World")
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    public Future<GreetingResponse> greetDefault() {
        return greet("World");
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "createGreeting",
            summary = "Create a custom greeting",
            description = "Accepts a name in the request body and returns a greeting")
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    public Future<GreetingResponse> createGreeting(GreetingRequest request) {
        String message = String.format(config.hello(), request.name());
        return Future.succeededFuture(new GreetingResponse(message));
    }

    @DELETE
    @Path("/{name}")
    @Operation(
            operationId = "deleteGreeting",
            summary = "Delete a greeting",
            description = "Deletes a greeting for the specified person")
    @ApiResponse(responseCode = "204", description = "Greeting deleted")
    public Future<Void> deleteGreeting(
            @Parameter(description = "The name to delete", required = true) @PathParam("name") String name) {
        log.info("Deleting greeting for: {}", name);
        return Future.succeededFuture();
    }

    @POST
    @Path("/greetings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "createGreetingResource",
            summary = "Create a greeting resource",
            description = "Creates a new greeting and returns 201 with Location header")
    @ApiResponse(
            responseCode = "201",
            description = "Greeting created",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    public Future<Response> createGreetingResource(GreetingRequest request) {
        String message = String.format(config.hello(), request.name());
        URI location = URI.create("/hello/greetings/" + request.name());
        Response response =
                Response.created(location).entity(new GreetingResponse(message)).build();
        return Future.succeededFuture(response);
    }

    @GET
    @Path("/greetings/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "getGreetingResource",
            summary = "Get a greeting resource",
            description = "Returns a greeting for the given name, or 404 if name is 'unknown'")
    @ApiResponse(
            responseCode = "200",
            description = "Greeting found",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "404", description = "Greeting not found")
    public Future<GreetingResponse> getGreetingResource(
            @Parameter(description = "Name to look up", required = true) @PathParam("name") String name) {
        if ("unknown".equals(name)) {
            throw new NotFoundException("Greeting not found for: " + name);
        }
        return Future.succeededFuture(new GreetingResponse(String.format(config.hello(), name)));
    }

    @GET
    @Path("/limited/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "greetLimited",
            summary = "Greet with a length limit",
            description = "Returns a greeting, or 429 if the name exceeds 5 characters")
    @ApiResponse(
            responseCode = "200",
            description = "Greeting returned",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "429", description = "Greeting limit exceeded")
    public Future<GreetingResponse> greetLimited(
            @Parameter(description = "Name to greet (max 5 characters)", required = true) @PathParam("name")
                    String name) {
        int limit = 5;
        if (name.length() > limit) {
            throw new GreetingLimitExceededException(limit);
        }
        return Future.succeededFuture(new GreetingResponse(String.format(config.hello(), name)));
    }

    // --- Secured endpoints demonstrating authentication and authorization ---

    /**
     * Secured endpoint requiring the 'user' role.
     *
     * @param sc the security context
     * @return personalized greeting for the authenticated user
     */
    @GET
    @Path("/secured")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Operation(
            operationId = "securedGreeting",
            summary = "Secured greeting (requires 'user' role)",
            description = "Returns a personalized greeting for the authenticated user",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Insufficient role")
    public Future<GreetingResponse> securedGreeting(@Parameter(hidden = true) SecurityContext sc) {
        String userId = sc.identity().actor().id();
        return Future.succeededFuture(new GreetingResponse("Hello, " + userId + "! (secured)"));
    }

    /**
     * Admin-only endpoint requiring the 'admin' role.
     *
     * @param sc the security context
     * @return admin greeting
     */
    @GET
    @Path("/admin")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    @Operation(
            operationId = "adminGreeting",
            summary = "Admin-only greeting (requires 'admin' role)",
            description = "Returns a greeting only accessible to admins",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Insufficient role")
    public Future<GreetingResponse> adminGreeting(@Parameter(hidden = true) SecurityContext sc) {
        String userId = sc.identity().actor().id();
        return Future.succeededFuture(new GreetingResponse("Admin panel: Hello, " + userId + "!"));
    }

    /**
     * Team-only endpoint requiring the 'team-lead' role granted by a Vert.x authorization provider.
     *
     * <p>No JWT in this example carries a {@code team-lead} role claim; the role is granted at
     * request time by {@code ExampleTeamAuthorizationProvider} (id {@code teams}) through the
     * opt-in {@code VertxAuthorizationImportModule}, demonstrating that provider-granted roles
     * satisfy {@code @RolesAllowed} exactly like claim-derived ones.
     *
     * @param sc the security context
     * @return team greeting for the provider-authorized user
     */
    @GET
    @Path("/team")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("team-lead")
    @Operation(
            operationId = "teamGreeting",
            summary = "Team-only greeting (requires provider-granted 'team-lead' role)",
            description = "Returns a greeting only accessible to subjects the Vert.x authorization"
                    + " provider recognizes as team leads",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Insufficient role")
    public Future<GreetingResponse> teamGreeting(@Parameter(hidden = true) SecurityContext sc) {
        String userId = sc.identity().actor().id();
        return Future.succeededFuture(new GreetingResponse("Team area: Hello, " + userId + "!"));
    }

    /**
     * Endpoint requiring both 'user' role and 'write' scope.
     *
     * @param sc the security context
     * @return scoped greeting
     */
    @GET
    @Path("/scoped")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Authorized(scopes = "write")
    @Operation(
            operationId = "scopedGreeting",
            summary = "Role + scope greeting (requires 'user' role AND 'write' scope)",
            description = "Demonstrates combined role and scope authorization",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "Successful greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = GreetingResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @ApiResponse(responseCode = "403", description = "Insufficient role or scope")
    public Future<GreetingResponse> scopedGreeting(@Parameter(hidden = true) SecurityContext sc) {
        String userId = sc.identity().actor().id();
        return Future.succeededFuture(new GreetingResponse("Scoped access: Hello, " + userId + "!"));
    }

    /**
     * Demonstrates security context injection and introspection.
     *
     * @param sc the framework security context
     * @return security context details
     */
    @GET
    @Path("/context")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Operation(
            operationId = "securityContextDemo",
            summary = "Security context demo (requires 'user' role)",
            description = "Returns details from the SecurityContext",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "Security context details",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SecurityContextInfo.class)))
    public Future<SecurityContextInfo> securityContextDemo(@Parameter(hidden = true) SecurityContext sc) {
        dev.vertique.security.PrincipalType actorType = sc.identity().actor().type();
        String userId = actorType == dev.vertique.security.PrincipalType.USER
                ? sc.identity().actor().id()
                : null;
        String clientId = sc.identity()
                .client()
                .map(dev.vertique.security.ClientRef::clientId)
                .orElse(
                        actorType == dev.vertique.security.PrincipalType.SERVICE
                                ? sc.identity().actor().id()
                                : null);
        String authMethod = sc.authentication().primaryMethod().id();
        java.util.Set<String> scopes = sc.authorization().valuesOf(dev.vertique.security.authz.AuthorityKind.SCOPE);
        // Credentials are not stored in the new model; reflect evidence presence instead
        boolean hasCredential = !sc.authentication().evidence().isEmpty();
        return Future.succeededFuture(new SecurityContextInfo(userId, clientId, authMethod, scopes, hasCredential));
    }

    /**
     * Demonstrates JAX-RS SecurityContext injection.
     *
     * @param jaxRsSc the JAX-RS security context
     * @return JAX-RS security context details
     */
    @GET
    @Path("/jaxrs-context")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Operation(
            operationId = "jaxRsContextDemo",
            summary = "JAX-RS SecurityContext demo (requires 'user' role)",
            description = "Demonstrates standard JAX-RS SecurityContext injection",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(
            responseCode = "200",
            description = "JAX-RS security context details",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = JaxRsContextInfo.class)))
    public Future<JaxRsContextInfo> jaxRsContextDemo(
            @Parameter(hidden = true) jakarta.ws.rs.core.SecurityContext jaxRsSc) {
        return Future.succeededFuture(new JaxRsContextInfo(
                jaxRsSc.getUserPrincipal() != null ? jaxRsSc.getUserPrincipal().getName() : null,
                jaxRsSc.isUserInRole("admin"),
                jaxRsSc.isUserInRole("user"),
                jaxRsSc.getAuthenticationScheme()));
    }

    /**
     * Always denied endpoint demonstrating {@code @DenyAll}.
     *
     * @return never returns (always throws 403)
     */
    @GET
    @Path("/denied")
    @Produces(MediaType.APPLICATION_JSON)
    @DenyAll
    @Operation(
            operationId = "deniedEndpoint",
            summary = "Always denied endpoint",
            description = "Always returns 403, even for authenticated users",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "403", description = "Access denied")
    public Future<GreetingResponse> deniedEndpoint() {
        return Future.succeededFuture(new GreetingResponse("You should never see this"));
    }

    // --- DTOs ---

    /**
     * Response DTO for greeting messages.
     *
     * @param message the greeting message
     */
    public record GreetingResponse(String message) {}

    /**
     * Request DTO for POST operations.
     *
     * @param name the name to greet
     */
    public record GreetingRequest(String name) {}

    /**
     * Response DTO containing framework SecurityContext details.
     *
     * @param userId the user ID from the security context
     * @param clientId the client ID from the security context
     * @param authMethod the authentication method used
     * @param scopes the set of scopes granted
     * @param hasCredential whether a credential is present
     */
    public record SecurityContextInfo(
            String userId,
            @Schema(nullable = true) String clientId,
            String authMethod,
            java.util.Set<String> scopes,
            boolean hasCredential) {}

    /**
     * Response DTO containing JAX-RS SecurityContext details.
     *
     * @param principal the principal name (user ID)
     * @param isAdmin whether the user has the 'admin' role
     * @param isUser whether the user has the 'user' role
     * @param authScheme the authentication scheme (e.g., "Bearer")
     */
    public record JaxRsContextInfo(String principal, boolean isAdmin, boolean isUser, String authScheme) {}
}
