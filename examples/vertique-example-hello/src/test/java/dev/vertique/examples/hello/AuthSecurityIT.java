// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.restassured.RestAssured;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for authentication and authorization in the example-hello application.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>JWT authentication using bearer tokens</li>
 *   <li>Role-based access control with {@code @RolesAllowed}</li>
 *   <li>Scope-based access control with {@code @Authorized}</li>
 *   <li>Security context injection (framework and JAX-RS)</li>
 *   <li>Combined role and scope requirements</li>
 *   <li>Public endpoints with {@code @PermitAll}</li>
 *   <li>Denied endpoints with {@code @DenyAll}</li>
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class AuthSecurityIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("hello", "Hello, %s!")
                    .put("management", new JsonObject().put("enabled", false))
                    .put("jaxrs", new JsonObject().put("validationStrategy", "openapi-contract"))
                    .put(
                            "rateLimit",
                            new JsonObject()
                                    .put(
                                            "policies",
                                            new JsonObject()
                                                    .put("hello-limited", RateLimitTestPolicies.helloLimited()))));

    private static JWTAuth jwtAuth;

    @BeforeAll
    static void setUp() {
        String specUrl = AuthSecurityIT.class.getResource("/openapi.json").toString();
        OpenApiValidationFilter openApiFilter =
                new OpenApiValidationFilter(OpenApiInteractionValidator.createForSpecificationUrl(specUrl)
                        .withLevelResolver(LevelResolver.create()
                                .withLevel("validation.response.status.unknown", ValidationReport.Level.IGNORE)
                                .withLevel("validation.response.body.unexpected", ValidationReport.Level.IGNORE)
                                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                                .build())
                        .build());

        // Create JWT auth with the SAME key as AppModule uses
        jwtAuth = JwtAuthFactory.fromSymmetricKey(
                app.vertx(), "HS256", "super-secret-key-for-example-app-minimum-256-bits-long!!");

        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
        RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter(), openApiFilter);
    }

    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    /**
     * Helper method to generate JWT tokens for tests.
     *
     * @param sub the subject (user ID)
     * @param roles the list of roles (can be null)
     * @param scope the space-separated scopes (can be null)
     * @return the generated JWT token
     */
    private static String generateToken(String sub, List<String> roles, String scope) {
        JsonObject claims = new JsonObject().put("sub", sub);
        if (roles != null && !roles.isEmpty()) {
            claims.put("roles", new JsonArray(roles));
        }
        if (scope != null) {
            claims.put("scope", scope);
        }
        return jwtAuth.generateToken(claims);
    }

    private static String generateTokenWithClaims(String sub, JsonObject extraClaims) {
        JsonObject claims = new JsonObject().put("sub", sub).mergeIn(extraClaims);
        return jwtAuth.generateToken(claims);
    }

    @Test
    @DisplayName("GET /hello/secured without auth returns 401")
    void noAuthReturns401() {
        given().when().get("/hello/secured").then().statusCode(401);
    }

    @Test
    @DisplayName("GET /hello/secured with invalid token returns 401")
    void invalidTokenReturns401() {
        given().header("Authorization", "Bearer invalid-token")
                .when()
                .get("/hello/secured")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("GET /hello/secured with valid JWT and 'user' role returns 200")
    void validJwtWithUserRoleReturns200() {
        String token = generateToken("testuser", List.of("user"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/secured")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", containsString("testuser"));
    }

    @Test
    @DisplayName("GET /hello/admin with wrong role returns 403")
    void validJwtWithWrongRoleReturns403() {
        String token = generateToken("testuser", List.of("viewer"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/admin")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("GET /hello/admin with 'admin' role returns 200")
    void validJwtWithAdminRoleReturns200() {
        String token = generateToken("admin1", List.of("admin", "user"), "read write");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/admin")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", containsString("admin1"));
    }

    @Test
    @DisplayName("GET /hello/World allows unauthenticated access (PermitAll)")
    void permitAllEndpointAllowsUnauthenticated() {
        given().when()
                .get("/hello/World")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, World!"));
    }

    @Test
    @DisplayName("GET /hello/denied returns 403 even with valid token (DenyAll)")
    void denyAllEndpointReturns403EvenWithValidToken() {
        String token = generateToken("admin1", List.of("admin", "user"), "read write");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/denied")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("GET /hello/scoped requires both 'user' role and 'write' scope")
    void scopedEndpointRequiresBothRoleAndScope() {
        String token = generateToken("testuser", List.of("user"), "read write");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/scoped")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", containsString("testuser"));
    }

    @Test
    @DisplayName("GET /hello/scoped rejects missing 'write' scope")
    void scopedEndpointRejectsMissingScope() {
        String token = generateToken("testuser", List.of("user"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/scoped")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("GET /hello/context returns SecurityContext details")
    void securityContextInjection() {
        String token = generateToken("user123", List.of("user"), "read write");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/context")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("userId", equalTo("user123"))
                .body("authMethod", equalTo("jwt"))
                .body("scopes", hasItems("read", "write"))
                .body("hasCredential", equalTo(true));
    }

    @Test
    @DisplayName("GET /hello/jaxrs-context returns JAX-RS SecurityContext with admin role")
    void jaxRsSecurityContextInjection() {
        String token = generateToken("user123", List.of("user", "admin"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/jaxrs-context")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("principal", equalTo("user123"))
                .body("isAdmin", equalTo(true))
                .body("isUser", equalTo(true))
                .body("authScheme", equalTo("BEARER"));
    }

    @Test
    @DisplayName("GET /hello/scoped authorizes using 'scp' claim as space-delimited string")
    void shouldAuthorizeWithScpAsString() {
        String token = generateTokenWithClaims(
                "testuser",
                new JsonObject().put("roles", new JsonArray().add("user")).put("scp", "read write"));

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/scoped")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("GET /hello/scoped authorizes using 'permissions' claim (Auth0)")
    void shouldAuthorizeWithPermissionsClaim() {
        String token = generateTokenWithClaims(
                "testuser",
                new JsonObject()
                        .put("roles", new JsonArray().add("user"))
                        .put("permissions", new JsonArray().add("write")));

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/scoped")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("GET /hello/team grants access via provider-granted 'team-lead' role")
    void shouldAllowAccessWithProviderGrantedRole() {
        // No team-lead role in the JWT claims — the grant must come from the
        // contributed Vert.x AuthorizationProvider (id "teams") for subject team-alice.
        String token = generateToken("team-alice", null, null);

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/team")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", containsString("team-alice"));
    }

    @Test
    @DisplayName("GET /hello/team rejects subjects the provider does not grant 'team-lead'")
    void shouldRejectTeamRouteWithoutProviderGrant() {
        String token = generateToken("testuser", List.of("user"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/team")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("GET /hello/jaxrs-context returns JAX-RS SecurityContext without admin role")
    void jaxRsSecurityContextNonAdmin() {
        String token = generateToken("user123", List.of("user"), "read");

        given().header("Authorization", "Bearer " + token)
                .when()
                .get("/hello/jaxrs-context")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("principal", equalTo("user123"))
                .body("isAdmin", equalTo(false))
                .body("isUser", equalTo(true))
                .body("authScheme", equalTo("BEARER"));
    }
}
