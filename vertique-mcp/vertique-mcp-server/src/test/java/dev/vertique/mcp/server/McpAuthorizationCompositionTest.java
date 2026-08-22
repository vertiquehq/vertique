// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.security.AuthorizationContributor;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that one production Dagger graph shares exactly one {@link SecurityPolicyEnforcer}
 * singleton between the REST {@link AuthorizationContributor} and {@link McpPolicyEnforcer} (T005
 * TP-003; {@code contracts/authorization-and-input-pipeline.md} § Frozen programmatic decision
 * operation).
 *
 * <p>Given a real Dagger graph combining {@code AuthModule} + {@code SecurityModule} (the REST
 * security modules), {@code McpServerModule} (the MCP server module), and an app-provided
 * {@link AuthorizationDecisionPoint} named in the composition. Framework wiring — the component
 * declaration, the app-provided decision point, and the externals a real application graph would
 * supply from elsewhere — lives in {@link McpAuthorizationCompositionTestFixture}; this method keeps
 * only the Given values, the one action, and the decisive assertions.
 */
class McpAuthorizationCompositionTest {

    @Test
    @DisplayName(
            "the REST authorization contributor and McpPolicyEnforcer resolve the same shared SecurityPolicyEnforcer")
    void shouldBindTheSharedEnforcerExactlyOnce() throws Exception {
        AuthorizationDecisionPoint appProvidedDecisionPoint = McpAuthorizationCompositionTestFixture.appDecisionPoint();
        McpAuthorizationCompositionTestFixture.WiringComponent component =
                McpAuthorizationCompositionTestFixture.buildComponent();

        SecurityPolicyEnforcer viaComponent = component.securityPolicyEnforcer();
        SecurityPolicyEnforcer viaAuthorizationContributor =
                McpAuthorizationCompositionTestFixture.enforcerFieldOf(component.authorizationContributor());
        SecurityPolicyEnforcer viaMcpPolicyEnforcer =
                component.mcpPolicyEnforcer().securityPolicyEnforcer();

        assertThat(viaAuthorizationContributor)
                .as("the REST authorization contributor must see the same enforcer singleton the graph resolves")
                .isSameAs(viaComponent);
        assertThat(viaMcpPolicyEnforcer)
                .as("McpPolicyEnforcer must see the same enforcer singleton the graph resolves")
                .isSameAs(viaComponent);
        // "The same selected decision point" is asserted directly rather than argued from the shared
        // enforcer instance: the enforcer selects its decision point once in its constructor, so reading
        // the selection back off the very instance both consumers hold is what actually proves the two
        // see the same evaluator.
        assertThat(McpAuthorizationCompositionTestFixture.selectedDecisionPointOf(viaMcpPolicyEnforcer))
                .as("the enforcer MCP holds must have selected exactly the app-provided decision point")
                .isSameAs(appProvidedDecisionPoint);
        assertThat(McpAuthorizationCompositionTestFixture.selectedDecisionPointOf(viaAuthorizationContributor))
                .as("the enforcer the REST contributor holds must have selected the same decision point")
                .isSameAs(appProvidedDecisionPoint);

        Optional<AuthorizationDecisionPoint> resolvedDecisionPoint = component.authorizationDecisionPoint();
        assertThat(resolvedDecisionPoint)
                .as("the graph must resolve exactly the one app-provided decision point — no second evaluator "
                        + "or selector binding")
                .containsSame(appProvidedDecisionPoint);
    }
}
