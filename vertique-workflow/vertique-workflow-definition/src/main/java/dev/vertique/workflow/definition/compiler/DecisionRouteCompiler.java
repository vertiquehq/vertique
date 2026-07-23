// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.definition.callbacks.NamedCondition;
import dev.vertique.workflow.definition.callbacks.NamedConditionRegistry;
import dev.vertique.workflow.definition.expression.CompiledExpression;
import dev.vertique.workflow.definition.expression.ExpressionEnv;
import dev.vertique.workflow.definition.expression.ExpressionFingerprint;
import dev.vertique.workflow.definition.expression.ExpressionParseException;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import dev.vertique.workflow.definition.schema.DecisionStep;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.CallbackId;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Compiles a {@link DecisionStep}'s route table into a synthesized resolver function and a stable
 * fingerprinted {@link CallbackId}.
 *
 * <p>The route table fingerprint embeds a type-prefixed representation of every route entry:
 * <ul>
 *   <li>{@code when} entries: {@code "when:<expressionFingerprint>-><to>|"}</li>
 *   <li>{@code condition} entries: {@code "condition:<conditionId>-><to>|"}</li>
 *   <li>default route: {@code "default:<to>"}</li>
 * </ul>
 * The symmetric {@code when:} / {@code condition:} prefix ensures the fingerprint is unambiguous
 * — a condition id cannot collide with an expression fingerprint, and the route type is always
 * explicit. Any semantic change to the route table produces a different fingerprint, which in turn
 * produces a different {@link CallbackId}, which in turn propagates to the plan hash via
 * {@link dev.vertique.workflow.dsl.WorkflowBuilder#decideWithCallbackId} (FR-WF-DEF-055, AC #8).
 *
 * <p>The synthesized {@code Function<Object, String>} resolver:
 * <ol>
 *   <li>Converts the state to a JSON-tree {@code Map} via Jackson (for {@code when} evaluation).</li>
 *   <li>Evaluates named conditions against the <em>raw</em> state instance directly.</li>
 *   <li>Walks routes in declaration order: for {@code when} entries, calls
 *       {@link ExpressionProfile#evaluateBoolean}; for {@code condition} entries, calls the
 *       registered {@link NamedCondition} function.</li>
 *   <li>Returns the first matching {@code to} target, or the {@code default} if nothing matches.</li>
 * </ol>
 *
 * <p>This class never imports any third-party expression-language types; all EL interaction is
 * through the {@link ExpressionProfile} abstraction (NFR-WF-DEF-007).
 */
@Singleton
public final class DecisionRouteCompiler {

    // --- Dependencies ---

    private final ExpressionProfile profile;
    private final NamedConditionRegistry conditionRegistry;
    private final ObjectMapper jsonMapper;

    // --- Construction ---

    /**
     * Constructs the compiler with the expression profile, named condition registry, and
     * JSON mapper factory.
     *
     * @param profile the expression profile used to compile and evaluate {@code when} expressions;
     *     non-null
     * @param conditionRegistry the registry of named conditions for {@code condition} routes;
     *     non-null
     * @param mapperFactory the factory producing the JSON mapper used for state serialization;
     *     non-null
     */
    @Inject
    public DecisionRouteCompiler(
            ExpressionProfile profile,
            NamedConditionRegistry conditionRegistry,
            WorkflowDefinitionMapperFactory mapperFactory) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.conditionRegistry = Objects.requireNonNull(conditionRegistry, "conditionRegistry");
        this.jsonMapper = Objects.requireNonNull(mapperFactory, "mapperFactory").jsonMapper();
    }

    // --- Public API ---

    /**
     * Compiles the route table of the given {@link DecisionStep} into a
     * {@link DecisionRouteResolver} containing a synthesized resolver function and a fingerprinted
     * {@link CallbackId}.
     *
     * @param definitionId the workflow definition id (incorporated into the callback id); non-null
     * @param version the workflow definition version (incorporated into the callback id)
     * @param step the decision step whose routes are compiled; non-null
     * @param env the compile-time expression environment for {@code when} expressions; non-null
     * @return the compiled route resolver; never null
     * @throws WorkflowDefinitionException if any {@code when} expression fails to parse or if a
     *     referenced named condition is not registered
     */
    public DecisionRouteResolver compileRoutes(
            String definitionId, long version, DecisionStep step, ExpressionEnv env) {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(env, "env");

        // Compile all routes up front: for 'when' entries, pre-compile the expression;
        // for 'condition' entries, look up the named condition function.
        List<CompiledRoute> compiled = new ArrayList<>(step.routes().size());
        StringBuilder fingerprintInput = new StringBuilder();

        for (DecisionStep.RouteEntry route : step.routes()) {
            if (route.when() != null) {
                CompiledExpression expr = compileWhenExpression(route.when(), env, step.id());
                String fp = profile.fingerprint(expr);
                // Prefix "when:" mirrors the "condition:" prefix used for named-condition routes,
                // making the fingerprint format symmetric and unambiguous across route types.
                fingerprintInput
                        .append("when:")
                        .append(fp)
                        .append("->")
                        .append(route.to())
                        .append("|");
                compiled.add(new CompiledRoute(route.to(), expr, null));
            } else {
                // condition route
                String conditionId = route.condition();
                @SuppressWarnings("unchecked")
                NamedCondition<Object> condition = (NamedCondition<Object>) conditionRegistry.lookup(conditionId);
                fingerprintInput
                        .append("condition:")
                        .append(conditionId)
                        .append("->")
                        .append(route.to())
                        .append("|");
                compiled.add(new CompiledRoute(route.to(), null, condition));
            }
        }
        fingerprintInput.append("default:").append(step.defaultRoute());

        // Build the fingerprinted callback id
        String fingerprintHex = ExpressionFingerprint.ofString(fingerprintInput.toString(), 8);
        CallbackId callbackId =
                new CallbackId("doc:" + definitionId + ":v" + version + ":" + step.id() + ".routes:" + fingerprintHex);

        // Capture locals for closure
        String defaultRoute = step.defaultRoute();
        List<CompiledRoute> routes = List.copyOf(compiled);

        // Synthesize the resolver function.
        // The function: (1) converts state to a JSON Map for 'when' evaluation,
        // (2) evaluates named conditions against the raw state.
        Function<Object, String> resolverFn = rawState -> {
            // Build the JSON-tree map for 'when' evaluation
            @SuppressWarnings("unchecked")
            Map<String, Object> stateMap = jsonMapper.convertValue(rawState, Map.class);
            Map<String, Object> evalEnv = new HashMap<>();
            evalEnv.put("state", stateMap);

            // Evaluate each named condition once and stash the boolean in evalEnv.
            // The expression profile reads these by id when a `when` expression references them,
            // and the route walk below reads them by id for `condition` routes — guaranteeing
            // each condition's user callback is invoked exactly once per decision evaluation
            // regardless of how many times it appears in the route table or in `when` expressions.
            for (CompiledRoute route : routes) {
                if (route.condition() != null) {
                    NamedCondition<Object> cond = route.condition();
                    evalEnv.computeIfAbsent(cond.id(), id -> cond.condition().apply(rawState));
                }
            }

            // Walk routes in declaration order
            for (CompiledRoute route : routes) {
                boolean matches;
                if (route.compiledExpression() != null) {
                    matches = profile.evaluateBoolean(route.compiledExpression(), evalEnv);
                } else {
                    // Reuse the boolean precomputed into evalEnv above (single callback invocation).
                    matches = Boolean.TRUE.equals(evalEnv.get(route.condition().id()));
                }
                if (matches) {
                    return route.to();
                }
            }
            return defaultRoute;
        };

        return new DecisionRouteResolver(resolverFn, callbackId);
    }

    // --- Private helpers ---

    /**
     * Compiles a {@code when} expression source string, wrapping any parse error in a
     * {@link WorkflowDefinitionException}.
     *
     * @param source the expression source; non-null
     * @param env the compile-time environment; non-null
     * @param stepId the step id for error context
     * @return the compiled expression; never null
     * @throws WorkflowDefinitionException if the expression fails to parse
     */
    private CompiledExpression compileWhenExpression(String source, ExpressionEnv env, String stepId) {
        try {
            return profile.compile(source, env);
        } catch (ExpressionParseException e) {
            throw new WorkflowDefinitionException(
                    "decision step '" + stepId + "': failed to compile route expression '" + source + "': "
                            + e.getMessage(),
                    e);
        }
    }

    // --- Private inner record ---

    /**
     * Internal representation of a compiled route entry: either a pre-compiled CEL expression
     * (for {@code when} routes) or a named condition function (for {@code condition} routes).
     *
     * <p>Exactly one of {@code compiledExpression} or {@code condition} is non-null.
     *
     * @param to the target step id
     * @param compiledExpression the pre-compiled CEL expression, or {@code null} for condition routes
     * @param condition the named condition, or {@code null} for when routes
     */
    private record CompiledRoute(String to, CompiledExpression compiledExpression, NamedCondition<Object> condition) {}
}
