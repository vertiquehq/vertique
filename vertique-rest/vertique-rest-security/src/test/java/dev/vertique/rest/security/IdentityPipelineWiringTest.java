// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.Component;
import dev.vertique.context.ContextRuntimeModule;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph-compilation proof for {@code AuthModule}'s {@link IdentityPipelineFactory}-backed
 * bindings, frozen by {@code contracts/identity-pipeline-factory.md} (T007).
 *
 * <p>Declares a component combining {@link AuthModule} and {@link SecurityModule} with the single
 * minimal stand-in this narrow request needs — {@link ContextRuntimeModule}, which supplies the
 * {@code ContextHolder} every collaborator in this graph requires — mirroring the narrow-exposure
 * pattern in {@code WebSocketImportComponentWiringTest}: the component requests only the three
 * bindings this proof needs, so the rest of {@code AuthModule}'s (much larger) surface is never
 * reached and never needs to be satisfied.
 *
 * <p>Proves that {@link AuthModule}'s {@code @Provides} bindings for {@link IdentityResolutionMiddleware}
 * and {@link SecurityPolicyEnforcer} resolve to the exact instances
 * {@link IdentityPipelineFactory#restIdentityResolution()} and
 * {@link IdentityPipelineFactory#policyEnforcer()} return — i.e. Dagger prefers the explicit
 * provider over either type's own {@code @Inject} constructor, so a graph including
 * {@code AuthModule} constructs exactly one instance of each, through the factory.
 *
 * <p>Expected initial result (before T007's production slice lands): red — neither
 * {@link IdentityPipelineFactory} nor the {@code AuthModule} providers that depend on it exist yet.
 */
class IdentityPipelineWiringTest {

    /**
     * Test component combining {@link AuthModule} and {@link SecurityModule} with the one stand-in
     * module ({@link ContextRuntimeModule}) this narrow request needs. Exposes exactly the three
     * bindings {@link #authModuleProvidesMiddlewareAndEnforcerFromTheFactory()} compares.
     */
    @Singleton
    @Component(modules = {AuthModule.class, SecurityModule.class, ContextRuntimeModule.class})
    interface WiringComponent {

        /**
         * Returns the single assembly-point factory the graph resolves.
         *
         * @return the {@link IdentityPipelineFactory} singleton
         */
        IdentityPipelineFactory factory();

        /**
         * Returns the {@link IdentityResolutionMiddleware} {@code AuthModule} provides.
         *
         * @return the middleware instance bound by {@code AuthModule}
         */
        IdentityResolutionMiddleware identityResolutionMiddleware();

        /**
         * Returns the {@link SecurityPolicyEnforcer} {@code AuthModule} provides.
         *
         * @return the enforcer instance bound by {@code AuthModule}
         */
        SecurityPolicyEnforcer securityPolicyEnforcer();
    }

    @Test
    @DisplayName("AuthModule binds the REST middleware and enforcer from the IdentityPipelineFactory")
    void authModuleProvidesMiddlewareAndEnforcerFromTheFactory() {
        WiringComponent component = DaggerIdentityPipelineWiringTest_WiringComponent.create();
        IdentityPipelineFactory factory = component.factory();

        assertSame(
                factory.restIdentityResolution(),
                component.identityResolutionMiddleware(),
                "AuthModule's IdentityResolutionMiddleware binding must be the factory's REST instance");
        assertSame(
                factory.policyEnforcer(),
                component.securityPolicyEnforcer(),
                "AuthModule's SecurityPolicyEnforcer binding must be the factory's memoized enforcer");
    }
}
