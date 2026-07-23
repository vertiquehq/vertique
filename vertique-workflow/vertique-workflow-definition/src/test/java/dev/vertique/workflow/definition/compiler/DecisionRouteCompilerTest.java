// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.NamedCondition;
import dev.vertique.workflow.definition.callbacks.NamedConditionRegistry;
import dev.vertique.workflow.definition.expression.ExpressionEnv;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.definition.schema.DecisionStep.RouteEntry;
import dev.vertique.workflow.registry.CallbackId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link DecisionRouteCompiler} against the functional requirements in WF-003 Slice F:
 *
 * <ul>
 *   <li>First-match semantics: routes are evaluated in declaration order and the first match wins.</li>
 *   <li>Mixed when/condition paths: a named condition coexisting with a {@code when} expression.</li>
 *   <li>Named conditions receive the raw state instance, not a JSON Map.</li>
 *   <li>Fingerprint stability: identical route tables → identical {@link CallbackId}; semantic
 *       changes → different {@link CallbackId} (AC #8 of PRD-WF-003).</li>
 * </ul>
 */
class DecisionRouteCompilerTest {

    // --- Test state ---

    record OrderState(String status, int priority) {}

    private ExpressionProfile profile;
    private NamedConditionRegistry conditionRegistry;
    private WorkflowDefinitionMapperFactory mapperFactory;
    private DecisionRouteCompiler compiler;

    @BeforeEach
    void setUp() {
        // WorkflowDefinitionMapperFactory has package-private constructor — mock it and stub
        // jsonMapper() to return a standard ObjectMapper suitable for test state conversion.
        mapperFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());
        profile = new CelExpressionProfile();
        conditionRegistry = new DefaultNamedConditionRegistry(Set.of());
        compiler = new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
    }

    // --- Helpers ---

    private ExpressionEnv envFor(Class<?> stateType, Set<String> conditionIds) {
        return new ExpressionEnv(stateType, conditionIds, Set.of());
    }

    private DecisionStep.RouteEntry whenRoute(String expression, String to) {
        return new DecisionStep.RouteEntry(expression, null, to);
    }

    private DecisionStep.RouteEntry conditionRoute(String conditionId, String to) {
        return new DecisionStep.RouteEntry(null, conditionId, to);
    }

    // --- First-match semantics ---

    @Nested
    @DisplayName("first-match semantics")
    class FirstMatchSemantics {

        @Test
        @DisplayName("first matching route wins")
        void firstRouteMatchWins() {
            List<RouteEntry> routes = List.of(
                    whenRoute("state.status == 'active'", "active-step"),
                    whenRoute("state.status == 'pending'", "pending-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver = compiler.compileRoutes("test-wf", 1L, step, env);

            OrderState state = new OrderState("active", 5);
            String next = resolver.resolverFn().apply(state);
            assertThat(next).isEqualTo("active-step");
        }

        @Test
        @DisplayName("second route matches when first does not")
        void secondRouteMatchesWhenFirstDoesNot() {
            List<RouteEntry> routes = List.of(
                    whenRoute("state.status == 'active'", "active-step"),
                    whenRoute("state.status == 'pending'", "pending-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver = compiler.compileRoutes("test-wf", 1L, step, env);

            OrderState state = new OrderState("pending", 5);
            String next = resolver.resolverFn().apply(state);
            assertThat(next).isEqualTo("pending-step");
        }

        @Test
        @DisplayName("default route used when no route matches")
        void defaultRouteWhenNoMatch() {
            List<RouteEntry> routes = List.of(whenRoute("state.status == 'active'", "active-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver = compiler.compileRoutes("test-wf", 1L, step, env);

            OrderState state = new OrderState("unknown", 5);
            String next = resolver.resolverFn().apply(state);
            assertThat(next).isEqualTo("default-step");
        }
    }

    // --- Mixed when/condition ---

    @Nested
    @DisplayName("mixed when/condition paths")
    class MixedWhenCondition {

        @Test
        @DisplayName("first-declared route wins even when both would match")
        void firstDeclaredWinsWhenBothMatch() {
            // Register a named condition that always returns true
            NamedConditionRegistry registry = new DefaultNamedConditionRegistry(
                    Set.of(b -> b.register(new NamedCondition<>("order.highPriority", OrderState.class, s -> true))));
            DecisionRouteCompiler mixedCompiler = new DecisionRouteCompiler(profile, registry, mapperFactory);

            List<RouteEntry> routes = List.of(
                    whenRoute("state.status == 'active'", "when-step"),
                    conditionRoute("order.highPriority", "condition-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of("order.highPriority"));
            DecisionRouteResolver resolver = mixedCompiler.compileRoutes("test-wf", 1L, step, env);

            // state.status == 'active' is true AND order.highPriority is true:
            // first route (when) wins
            OrderState state = new OrderState("active", 10);
            assertThat(resolver.resolverFn().apply(state)).isEqualTo("when-step");
        }
    }

    // --- Named condition receives raw state ---

    @Nested
    @DisplayName("named condition receives raw state")
    class NamedConditionReceivesRawState {

        @Test
        @DisplayName("condition function receives OrderState instance, not a Map")
        void conditionReceivesRawStateInstance() {
            AtomicBoolean receivedRawState = new AtomicBoolean(false);
            NamedConditionRegistry registry = new DefaultNamedConditionRegistry(
                    Set.of(b -> b.register(new NamedCondition<>("order.check", OrderState.class, s -> {
                        // Verify it's an instance of OrderState, not a Map
                        receivedRawState.set(s instanceof OrderState);
                        return true;
                    }))));
            DecisionRouteCompiler rawStateCompiler = new DecisionRouteCompiler(profile, registry, mapperFactory);

            List<RouteEntry> routes = List.of(conditionRoute("order.check", "checked-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of("order.check"));
            DecisionRouteResolver resolver = rawStateCompiler.compileRoutes("test-wf", 1L, step, env);

            resolver.resolverFn().apply(new OrderState("active", 1));
            assertThat(receivedRawState).isTrue();
        }

        @Test
        @DisplayName(
                "named condition is invoked exactly once per decision evaluation, even when referenced by multiple routes")
        void namedConditionInvokedOncePerEvaluation() {
            // Counter increments every time the condition callback runs. A naive implementation
            // that evaluates the condition once when building evalEnv AND again when walking the
            // `condition` route would double-invoke; with the precompute-once-and-reuse fix the
            // counter must be exactly 1 per resolver call regardless of how many routes reference
            // the same condition id.
            AtomicInteger invocations = new AtomicInteger(0);
            NamedConditionRegistry registry = new DefaultNamedConditionRegistry(
                    Set.of(b -> b.register(new NamedCondition<>("order.flag", OrderState.class, s -> {
                        invocations.incrementAndGet();
                        return false; // never matches so we exercise the full walk
                    }))));
            DecisionRouteCompiler localCompiler = new DecisionRouteCompiler(profile, registry, mapperFactory);

            // Two routes referencing the same named condition + a default.
            List<RouteEntry> routes =
                    List.of(conditionRoute("order.flag", "first-match"), conditionRoute("order.flag", "second-match"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");
            ExpressionEnv env = envFor(OrderState.class, Set.of("order.flag"));
            DecisionRouteResolver resolver = localCompiler.compileRoutes("test-wf", 1L, step, env);

            String result = resolver.resolverFn().apply(new OrderState("active", 1));

            assertThat(result).isEqualTo("default-step");
            assertThat(invocations).hasValue(1);
        }
    }

    // --- Fingerprint stability ---

    @Nested
    @DisplayName("fingerprint stability (AC #8)")
    class FingerprintStability {

        @Test
        @DisplayName("identical route tables produce identical fingerprinted callback id")
        void identicalRouteTablesProduceIdenticalCallbackId() {
            List<RouteEntry> routes = List.of(
                    whenRoute("state.status == 'active'", "active-step"),
                    whenRoute("state.priority > 5", "high-priority-step"));
            DecisionStep step = new DecisionStep("route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver1 = compiler.compileRoutes("test-wf", 1L, step, env);
            DecisionRouteResolver resolver2 = compiler.compileRoutes("test-wf", 1L, step, env);

            assertThat(resolver1.fingerprintedCallbackId()).isEqualTo(resolver2.fingerprintedCallbackId());
        }

        @Test
        @DisplayName("reordering routes produces different fingerprint")
        void reorderingRoutesDifferentFingerprint() {
            List<RouteEntry> routes1 = List.of(
                    whenRoute("state.status == 'active'", "active-step"), whenRoute("state.priority > 5", "high-step"));
            List<RouteEntry> routes2 = List.of(
                    whenRoute("state.priority > 5", "high-step"), whenRoute("state.status == 'active'", "active-step"));
            DecisionStep step1 = new DecisionStep("route", routes1, "default-step");
            DecisionStep step2 = new DecisionStep("route", routes2, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver1 = compiler.compileRoutes("test-wf", 1L, step1, env);
            DecisionRouteResolver resolver2 = compiler.compileRoutes("test-wf", 1L, step2, env);

            assertThat(resolver1.fingerprintedCallbackId()).isNotEqualTo(resolver2.fingerprintedCallbackId());
        }

        @Test
        @DisplayName("changing 'to' target produces different fingerprint")
        void changingToTargetDifferentFingerprint() {
            List<RouteEntry> routes1 = List.of(whenRoute("state.status == 'active'", "step-a"));
            List<RouteEntry> routes2 = List.of(whenRoute("state.status == 'active'", "step-b"));
            DecisionStep step1 = new DecisionStep("route", routes1, "default-step");
            DecisionStep step2 = new DecisionStep("route", routes2, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver r1 = compiler.compileRoutes("test-wf", 1L, step1, env);
            DecisionRouteResolver r2 = compiler.compileRoutes("test-wf", 1L, step2, env);

            assertThat(r1.fingerprintedCallbackId()).isNotEqualTo(r2.fingerprintedCallbackId());
        }

        @Test
        @DisplayName("changing the default produces different fingerprint")
        void changingDefaultDifferentFingerprint() {
            List<RouteEntry> routes = List.of(whenRoute("state.status == 'active'", "active-step"));
            DecisionStep step1 = new DecisionStep("route", routes, "default-a");
            DecisionStep step2 = new DecisionStep("route", routes, "default-b");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver r1 = compiler.compileRoutes("test-wf", 1L, step1, env);
            DecisionRouteResolver r2 = compiler.compileRoutes("test-wf", 1L, step2, env);

            assertThat(r1.fingerprintedCallbackId()).isNotEqualTo(r2.fingerprintedCallbackId());
        }

        @Test
        @DisplayName("semantically different when expressions produce different fingerprint")
        void semanticExpressionChangeDifferentFingerprint() {
            List<RouteEntry> routes1 = List.of(whenRoute("state.status == 'active'", "step"));
            List<RouteEntry> routes2 = List.of(whenRoute("state.status == 'inactive'", "step"));
            DecisionStep step1 = new DecisionStep("route", routes1, "default-step");
            DecisionStep step2 = new DecisionStep("route", routes2, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver r1 = compiler.compileRoutes("test-wf", 1L, step1, env);
            DecisionRouteResolver r2 = compiler.compileRoutes("test-wf", 1L, step2, env);

            assertThat(r1.fingerprintedCallbackId()).isNotEqualTo(r2.fingerprintedCallbackId());
        }

        @Test
        @DisplayName("fingerprinted callback id contains the definitionId, version, and step id")
        void callbackIdContainsIdentifiableComponents() {
            List<RouteEntry> routes = List.of(whenRoute("state.status == 'active'", "step"));
            DecisionStep step = new DecisionStep("my-route", routes, "default-step");

            ExpressionEnv env = envFor(OrderState.class, Set.of());
            DecisionRouteResolver resolver = compiler.compileRoutes("order-wf", 2L, step, env);

            String value = resolver.fingerprintedCallbackId().value();
            assertThat(value).startsWith("doc:order-wf:v2:my-route.routes:");
        }
    }
}
