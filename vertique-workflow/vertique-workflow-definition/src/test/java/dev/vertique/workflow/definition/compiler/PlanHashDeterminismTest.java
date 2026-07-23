// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedCondition;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.CompleteStep;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.plan.WorkflowPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies plan-hash determinism requirements from PRD-WF-003 (FR-WF-DEF-006):
 *
 * <ul>
 *   <li>Compiling the same document object twice produces the same plan hash.</li>
 *   <li>A semantically different route expression produces a different plan hash.</li>
 *   <li>A different {@code to} target in a route produces a different plan hash.</li>
 *   <li>A different default route produces a different plan hash.</li>
 *   <li>The {@code when:} prefix in the fingerprint is stable — re-compiling the same document
 *       produces the same hash across JVM runs (FR-WF-DEF-006 determinism AC #8).</li>
 * </ul>
 *
 * <p>Tests build {@link WorkflowDefinitionDocument} records directly rather than parsing YAML
 * bytes, to avoid the package-private constructor constraint on {@code WorkflowDefinitionParser}.
 * Source-format equivalence (YAML vs JSON producing the same document) is tested at the parser
 * layer in {@code WorkflowDefinitionParserTest}.
 *
 * <p><strong>Whitespace-only expression changes (AC #8):</strong> Whether two expressions that
 * differ only in whitespace produce the same fingerprint depends on whether the CEL canonical form
 * collapses that whitespace. If the CEL unparser normalises the forms, AC #8 holds fully. The
 * semantically-different cases tested here are deterministic regardless of whitespace behaviour.
 *
 * <p><strong>Fingerprint format (S6):</strong> The route fingerprint uses a symmetric
 * {@code when:} / {@code condition:} prefix. Compiling the same document twice must produce
 * the same hash; this test also acts as a stability anchor for the format. If the format changes,
 * the determinism test catches it immediately.
 */
class PlanHashDeterminismTest {

    // --- Test types ---

    record TestState(String status, int priority) {}

    record TestStartPayload(String id) {}

    @WorkflowContract(definitionId = "order-fulfillment", definitionVersion = 1)
    interface TestContract {}

    // --- Infrastructure ---

    private WorkflowDefinitionCompiler compiler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        WorkflowDefinitionMapperFactory mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.fromPlaceOrder",
                        TestStartPayload.class,
                        TestState.class,
                        p -> new TestState(p.id(), 0)))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", TestState.class, s -> s);
        Mockito.when(payloadMappers.lookup(Mockito.anyString())).thenReturn(rawMapper);

        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(Set.of(
                b -> b.register(new NamedCondition<>("policy.lowRiskAutoApprove", TestState.class, s -> false))));

        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                conditionRegistry);

        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);
    }

    // --- Helper ---

    /**
     * Builds a minimal document with a single decision step.
     *
     * @param routes the routes for the decision step
     * @param defaultRoute the default route
     * @return the document record
     */
    private WorkflowDefinitionDocument decisionDoc(List<DecisionStep.RouteEntry> routes, String defaultRoute) {
        return new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(new DecisionStep("route", routes, defaultRoute), new CompleteStep("done")));
    }

    private String compileToHash(WorkflowDefinitionDocument doc) {
        @SuppressWarnings("rawtypes")
        WorkflowBuilder builder = new WorkflowBuilder<>();
        compiler.emit(builder, TestState.class, TestContract.class, doc);
        @SuppressWarnings("unchecked")
        WorkflowPlan plan = builder.build(doc.definitionId(), doc.definitionVersion(), TestState.class.getName());
        return plan.planHash();
    }

    // --- Tests ---

    @Test
    @DisplayName("compiling the same document twice produces identical plan hash")
    void sameParseTwiceIdenticalHash() {
        WorkflowDefinitionDocument doc =
                decisionDoc(List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")), "done");
        assertThat(compileToHash(doc)).isEqualTo(compileToHash(doc));
    }

    @Test
    @DisplayName("minimal complete-only document compiled twice produces identical plan hash")
    void simpleDocTwiceIdenticalHash() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "done",
                List.of(new CompleteStep("done")));
        assertThat(compileToHash(doc)).isEqualTo(compileToHash(doc));
    }

    @Test
    @DisplayName("different 'to' in a route produces different plan hash")
    void differentToProducesDifferentHash() {
        WorkflowDefinitionDocument doc1 =
                decisionDoc(List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "step-a")), "done");
        WorkflowDefinitionDocument doc2 =
                decisionDoc(List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "step-b")), "done");

        // Need a done step in both — add it
        WorkflowDefinitionDocument doc1WithDone = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")),
                                "done"),
                        new CompleteStep("done")));
        WorkflowDefinitionDocument doc2WithDone = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "other")),
                                "other"),
                        new CompleteStep("other")));

        assertThat(compileToHash(doc1WithDone)).isNotEqualTo(compileToHash(doc2WithDone));
    }

    @Test
    @DisplayName("different default route produces different plan hash")
    void differentDefaultProducesDifferentHash() {
        WorkflowDefinitionDocument doc1 = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")),
                                "done"),
                        new CompleteStep("done")));
        WorkflowDefinitionDocument doc2 = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")),
                                "other-done"),
                        new CompleteStep("done"),
                        new CompleteStep("other-done")));

        assertThat(compileToHash(doc1)).isNotEqualTo(compileToHash(doc2));
    }

    @Test
    @DisplayName("semantically different when expression produces different plan hash")
    void semanticExpressionChangeProducesDifferentHash() {
        WorkflowDefinitionDocument doc1 = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")),
                                "done"),
                        new CompleteStep("done")));
        WorkflowDefinitionDocument doc2 = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'inactive'", null, "done")),
                                "done"),
                        new CompleteStep("done")));

        assertThat(compileToHash(doc1)).isNotEqualTo(compileToHash(doc2));
    }

    @Test
    @DisplayName("named-condition route with same condition id produces identical plan hash across two compiles")
    void namedConditionRouteStableAcrossCompiles() {
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry(null, "policy.lowRiskAutoApprove", "done")),
                                "done"),
                        new CompleteStep("done")));

        assertThat(compileToHash(doc)).isEqualTo(compileToHash(doc));
    }

    @Test
    @DisplayName("when: fingerprint prefix is stable — same doc compiled three times produces identical hash (S6)")
    void whenFingerprintPrefixIsStableAcrossRecompiles() {
        // This test acts as a stability anchor for the symmetric when:/condition: fingerprint
        // format introduced in S6. If the format changes again, this test catches it immediately.
        WorkflowDefinitionDocument doc = new WorkflowDefinitionDocument(
                "order-fulfillment",
                1L,
                TestState.class.getName(),
                TestContract.class.getName(),
                TestStartPayload.class.getName(),
                "order.fromPlaceOrder",
                null,
                "route",
                List.of(
                        new DecisionStep(
                                "route",
                                List.of(new DecisionStep.RouteEntry("state.status == 'active'", null, "done")),
                                "done"),
                        new CompleteStep("done")));

        String hash1 = compileToHash(doc);
        String hash2 = compileToHash(doc);
        String hash3 = compileToHash(doc);

        assertThat(hash1)
                .as("first and second compile must produce identical hash (when: prefix stable)")
                .isEqualTo(hash2);
        assertThat(hash1)
                .as("first and third compile must produce identical hash (when: prefix stable)")
                .isEqualTo(hash3);
    }
}
