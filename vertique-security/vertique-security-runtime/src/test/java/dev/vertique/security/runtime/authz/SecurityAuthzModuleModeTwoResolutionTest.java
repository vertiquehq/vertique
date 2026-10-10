// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves, through the {@link Authorizer} that {@link SecurityAuthzModule} wires, that an application
 * {@link PrincipalAuthorityResolver} whose future never completes is bounded by the configured
 * resolution timeout: the Mode-2 authorizer denies with {@link
 * AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED} instead of waiting forever.
 *
 * <p>{@code ReconstructedAuthorityResolvingAuthorizerTest} and {@code
 * TimeoutPrincipalAuthorityResolverTest} cover the two decorators on their own; this test covers the
 * module wiring that joins them to the application's resilience runtime and configured timeout.
 */
class SecurityAuthzModuleModeTwoResolutionTest {

    private static final long RESOLUTION_TIMEOUT_MS = 100L;
    private static final long AWAIT_MS = 3_000L;
    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() throws Exception {
        VERTX.close().toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** Supplies the Vert.x instance, a hung resolver and a short resolution timeout. */
    @Module
    interface WiringModule {

        @Provides
        @Singleton
        static Vertx vertx() {
            return VERTX;
        }

        @Provides
        @Singleton
        static PrincipalAuthorityResolver hungResolver() {
            return key -> Promise.<AuthorizationClaims>promise().future();
        }

        @Provides
        @Singleton
        static PrincipalAuthorityResolutionConfig resolutionConfig() {
            return new PrincipalAuthorityResolutionConfig(RESOLUTION_TIMEOUT_MS);
        }
    }

    @Singleton
    @Component(modules = {SecurityAuthzModule.class, WiringModule.class})
    interface WiredComponent {

        Authorizer authorizer();
    }

    @Test
    @DisplayName("a resolver that never completes is bounded by the configured timeout through the wired "
            + "Authorizer: the reconstructed context is denied with AUTHORITY_RESOLUTION_FAILED")
    void hungResolverDeniesAReconstructedContextWithinTheConfiguredTimeout() throws Exception {
        Authorizer authorizer = DaggerSecurityAuthzModuleModeTwoResolutionTest_WiredComponent.create()
                .authorizer();
        SecurityContext reconstructed = reconstructedContext();

        AuthorizationDecision decision = authorizer
                .authorize(new AuthorizationRequest(
                        reconstructed, "cms.content.read", new ResourceRef("content", "doc-1", Map.of()), Map.of()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(AWAIT_MS, TimeUnit.MILLISECONDS);

        assertFalse(decision.permitted());
        assertEquals(AuthzReasonCodes.AUTHORITY_RESOLUTION_FAILED, decision.reasonCode());
    }

    private static SecurityContext reconstructedContext() {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return SecurityContexts.assembleReconstructed(
                identity,
                authentication,
                AuthorizationClaims.empty(),
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY));
    }
}
