// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.rest.security.AuthModule;
import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.SecurityModule;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Framework wiring for {@link McpAuthorizationCompositionTest} (T005 TP-003): the real production
 * Dagger graph — {@code AuthModule} + {@code SecurityModule} (the REST security modules),
 * {@code McpServerModule} (the MCP server module), and the externals a real application graph would
 * supply from elsewhere ({@link ContextHolder}, the app-provided {@link AuthorizationDecisionPoint}).
 *
 * <p>Nothing here decides Given values or asserts outcomes — that stays in the test method. This
 * class only builds the graph and exposes the one reflective seam ({@link #enforcerFieldOf}) the
 * real {@link AuthorizationContributor} needs because it exposes no test accessor of its own (it is
 * owned by a different task and must not be modified here).
 */
final class McpAuthorizationCompositionTestFixture {

    private McpAuthorizationCompositionTestFixture() {}

    /**
     * Returns the one app-provided {@link AuthorizationDecisionPoint} the graph is composed with, so
     * the test can assert the graph resolves exactly this instance.
     *
     * @return the shared app-provided decision point instance; never {@code null}
     */
    static AuthorizationDecisionPoint appDecisionPoint() {
        return AppDecisionPointModule.INSTANCE;
    }

    /**
     * Builds the production-shaped Dagger graph once.
     *
     * @return the built component; never {@code null}
     */
    static WiringComponent buildComponent() {
        return DaggerMcpAuthorizationCompositionTestFixture_WiringComponent.create();
    }

    /**
     * Reads the private {@code enforcer} field {@link AuthorizationContributor} holds, since that
     * class — owned outside this task — exposes no test accessor. Confined to this one reflective
     * seam rather than spread across the test.
     *
     * @param contributor the resolved contributor instance; must not be {@code null}
     * @return the {@link SecurityPolicyEnforcer} the contributor was constructed with
     * @throws ReflectiveOperationException if the field cannot be read
     */
    static SecurityPolicyEnforcer enforcerFieldOf(AuthorizationContributor contributor)
            throws ReflectiveOperationException {
        Field field = AuthorizationContributor.class.getDeclaredField("enforcer");
        field.setAccessible(true);
        return (SecurityPolicyEnforcer) field.get(contributor);
    }

    /**
     * Reads back the {@link AuthorizationDecisionPoint} an enforcer instance selected in its
     * constructor, so "both consumers see the same selected decision point" can be asserted directly
     * instead of inferred from the two consumers sharing an enforcer instance.
     *
     * @param enforcer the enforcer instance to read; must not be {@code null}
     * @return the decision point that enforcer selected, or {@code null} when it selected none
     * @throws ReflectiveOperationException if the field cannot be read
     */
    static AuthorizationDecisionPoint selectedDecisionPointOf(SecurityPolicyEnforcer enforcer)
            throws ReflectiveOperationException {
        Field field = SecurityPolicyEnforcer.class.getDeclaredField("decisionPoint");
        field.setAccessible(true);
        return (AuthorizationDecisionPoint) field.get(enforcer);
    }

    /** The production-shaped test component: REST security modules + the MCP server module. */
    @Singleton
    @Component(
            modules = {
                AuthModule.class,
                SecurityModule.class,
                McpServerModule.class,
                ExternalsModule.class,
                AppDecisionPointModule.class
            })
    interface WiringComponent {

        /**
         * Resolves the shared enforcer directly from the graph.
         *
         * @return the singleton {@link SecurityPolicyEnforcer}
         */
        SecurityPolicyEnforcer securityPolicyEnforcer();

        /**
         * Resolves the REST authorization contributor — the enforcer's other real consumer.
         *
         * @return the singleton {@link AuthorizationContributor}
         */
        AuthorizationContributor authorizationContributor();

        /**
         * Resolves the package-private MCP policy enforcer — the enforcer's MCP-side consumer.
         *
         * @return the singleton {@link McpPolicyEnforcer}
         */
        McpPolicyEnforcer mcpPolicyEnforcer();

        /**
         * Resolves the optional app-provided decision point the graph selected.
         *
         * @return the resolved optional decision point
         */
        Optional<AuthorizationDecisionPoint> authorizationDecisionPoint();
    }

    /**
     * Supplies the bindings a real application graph owns outside the REST security and MCP server
     * modules: a no-op {@link ContextHolder} ({@link SecurityPolicyEnforcer#decide} is not exercised
     * by this wiring-only proof, so no real per-request storage is needed).
     */
    @Module
    abstract static class ExternalsModule {
        private ExternalsModule() {}

        /**
         * Supplies a {@link ContextHolder} that resolves nothing and discards every binding.
         *
         * @return the no-op context holder
         */
        @Provides
        @Singleton
        static ContextHolder contextHolder() {
            return NO_OP_CONTEXT_HOLDER;
        }
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /**
     * Supplies the one app-provided {@link AuthorizationDecisionPoint} named in the composition,
     * satisfying {@code AuthModule}'s {@code @BindsOptionalOf} seam so
     * {@code Optional<AuthorizationDecisionPoint>} resolves present.
     */
    @Module
    abstract static class AppDecisionPointModule {
        private AppDecisionPointModule() {}

        /** The single shared instance every consumer in the graph must resolve. */
        static final AuthorizationDecisionPoint INSTANCE = new StubDecisionPoint();

        /**
         * Provides the app-provided decision point.
         *
         * @return the shared instance; never {@code null}
         */
        @Provides
        @Singleton
        static AuthorizationDecisionPoint decisionPoint() {
            return INSTANCE;
        }
    }

    /** A minimal {@link AuthorizationDecisionPoint} that always permits; never exercised by decide(). */
    private static final class StubDecisionPoint implements AuthorizationDecisionPoint {
        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            return Future.succeededFuture(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }
    }
}
