// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_Legacy;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutSchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.DaggerTypedSyntheticComponents_WithoutSecurityModules;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.TypedSyntheticComponents;
import dev.vertique.rest.jaxrs.synthetic.TypedSyntheticObservations;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The unchanged-behavior controls of the typed synthetic operation proofs, using only the legacy
 * synthetic operation factories: the supported security composition built from the framework's own
 * {@code RestModule}, {@code AuthModule} and {@code SecurityModule} authenticates, then allows or
 * denies a role-restricted and an authenticated-only operation, and the same operations are refused
 * at install when the security modules or the scheme handler are missing. Each test builds a fresh
 * deployment, so one failure cannot mask another.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class SyntheticCompositionControlIT {

    private static final String ORIGIN = "legacy synthetic operation";
    private static final String SCHEME = "bearerAuth";

    @Test
    @DisplayName("A role-restricted legacy operation authenticates first, then denies or serves")
    void legacyRoleRestrictedOperationAuthenticatesThenAuthorizes() throws Exception {
        TypedSyntheticComponents.Legacy composition = DaggerTypedSyntheticComponents_Legacy.create();
        TypedSyntheticObservations observed = composition.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(composition::httpVerticle)) {
            // Given a deployment of the supported composition with a role-restricted synthetic operation
            // When a caller without credentials, a caller without the role, and a caller with the role request it
            HttpResponse<Buffer> anonymous = deployment.get("/legacy/roles", null, null, null);
            HttpResponse<Buffer> withoutRole = deployment.get("/legacy/roles", "alice", "reader", null);
            int servedBeforeAllowed = observed.terminalRuns("legacy:roles");
            HttpResponse<Buffer> withRole = deployment.get("/legacy/roles", "alice", "admin", null);

            // Then authentication denies the first with 401, authorization denies the second with 403,
            // and only the third reaches the terminal handler
            assertEquals(401, anonymous.statusCode(), "no credential");
            assertEquals(403, withoutRole.statusCode(), "authenticated without the role");
            assertEquals(0, servedBeforeAllowed, "a denied caller must not reach the terminal handler");
            assertEquals(200, withRole.statusCode(), "authenticated with the role");
            assertEquals("ok:legacy:roles", withRole.bodyAsString());
            assertEquals(1, observed.terminalRuns("legacy:roles"), "the allowed caller reaches the terminal once");
        }
    }

    @Test
    @DisplayName("An authenticated-only legacy operation denies anonymous callers and serves authenticated ones")
    void legacyAuthenticatedOperationRequiresAuthentication() throws Exception {
        TypedSyntheticComponents.Legacy composition = DaggerTypedSyntheticComponents_Legacy.create();
        TypedSyntheticObservations observed = composition.observations();
        try (TypedSyntheticDeployment deployment = TypedSyntheticDeployment.deploy(composition::httpVerticle)) {
            // Given a deployment of the supported composition with an authenticated-only synthetic operation
            // When a caller without credentials and an authenticated caller request it
            HttpResponse<Buffer> anonymous = deployment.get("/legacy/authenticated", null, null, null);
            int servedBeforeAuthenticated = observed.terminalRuns("legacy:authenticated");
            HttpResponse<Buffer> authenticated = deployment.get("/legacy/authenticated", "alice", null, null);

            // Then the anonymous caller is denied with 401 and the authenticated caller is served once
            assertEquals(401, anonymous.statusCode(), "no credential");
            assertEquals(0, servedBeforeAuthenticated, "a denied caller must not reach the terminal handler");
            assertEquals(200, authenticated.statusCode(), "authenticated caller");
            assertEquals(1, observed.terminalRuns("legacy:authenticated"));
        }
    }

    @Test
    @DisplayName("A restrictive legacy operation is refused at install when the security modules are missing")
    void legacyOperationIsRefusedWithoutTheSecurityModules() throws Exception {
        // Given a composition with a bearer scheme handler but neither AuthModule nor SecurityModule
        TypedSyntheticComponents.WithoutSecurityModules composition =
                DaggerTypedSyntheticComponents_WithoutSecurityModules.create();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            for (SyntheticOperation operation : List.of(
                    SyntheticOperation.withRoles(ORIGIN, "legacy:roles", SCHEME, "legacy", List.of("admin")),
                    SyntheticOperation.authenticated(ORIGIN, "legacy:authenticated", SCHEME, "legacy"))) {
                // When the restrictive operation is installed
                RestConfigurationException refusal =
                        probe.refusal(composition.syntheticOperationInstaller(), "/legacy/x", operation);

                // Then the install is refused for the missing security modules and leaves no route
                assertEquals(
                        List.of(RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE),
                        TypedSyntheticInstallProbe.routeViolationTypes(refusal),
                        operation.operationId());
            }
        }
    }

    @Test
    @DisplayName("A restrictive legacy operation is refused at install when no scheme handler is configured")
    void legacyOperationIsRefusedWithoutASchemeHandler() throws Exception {
        // Given the supported security modules but no handler for the bearer scheme
        TypedSyntheticComponents.WithoutSchemeHandler composition =
                DaggerTypedSyntheticComponents_WithoutSchemeHandler.create();
        try (TypedSyntheticInstallProbe probe = new TypedSyntheticInstallProbe()) {
            for (SyntheticOperation operation : List.of(
                    SyntheticOperation.withRoles(ORIGIN, "legacy:roles", SCHEME, "legacy", List.of("admin")),
                    SyntheticOperation.authenticated(ORIGIN, "legacy:authenticated", SCHEME, "legacy"))) {
                // When the operation naming that scheme is installed
                RestConfigurationException refusal =
                        probe.refusal(composition.syntheticOperationInstaller(), "/legacy/x", operation);

                // Then the install is refused for the scheme without a handler and leaves no route
                assertEquals(
                        List.of(SecurityPolicyViolation.ViolationType.OPENAPI_SECURITY_WITHOUT_HANDLER),
                        TypedSyntheticInstallProbe.policyViolationTypes(refusal),
                        operation.operationId());
                assertFalse(refusal.getMessage().isBlank());
            }
        }
    }
}
