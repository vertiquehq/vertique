// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import java.util.Map;

/**
 * The replaceable expression profile SPI for workflow routing expressions.
 *
 * <p>An {@code ExpressionProfile} encapsulates a specific expression language (EL) engine behind a
 * Vertique-owned contract. Only the concrete implementation class (e.g.,
 * {@code CelExpressionProfile}) imports any third-party EL library. All other classes in the
 * {@code workflow-definition} module interact exclusively with this interface and the
 * {@link CompiledExpression} / {@link ExpressionEnv} value types.
 *
 * <p>Replacing the EL engine requires only a new implementation class and a single Dagger
 * {@code @Binds} change in {@code WorkflowDefinitionModule} — no other class changes (NFR-WF-DEF-007).
 *
 * <h2>Evaluation environment contract</h2>
 * The {@code evalEnv} map passed to {@link #evaluate} and {@link #evaluateBoolean} must contain:
 * <ul>
 *   <li>A {@code "state"} entry whose value is a {@code Map<String, Object>} produced by
 *       {@code ObjectMapper.convertValue(stateInstance, Map.class)} — i.e., a Jackson JSON-tree
 *       representation of the workflow state POJO.</li>
 *   <li>One {@code Boolean} entry per named-condition id declared in the {@link ExpressionEnv}
 *       passed at compile time (value {@code true} or {@code false}, pre-evaluated by the caller
 *       before invoking {@code evaluate}).</li>
 * </ul>
 * Callers prepare the full environment map; the profile does not perform any additional state
 * conversion at evaluation time.
 *
 * <h2>Safety requirements</h2>
 * Implementations MUST honour the bounds declared at construction (max source length, max AST
 * depth, max comprehension iterations) and MUST NOT access ambient runtime state (clock, env vars,
 * network, filesystem, DI container, arbitrary Java reflection).
 */
public interface ExpressionProfile {

    /**
     * Parses, type-checks, and canonicalises the given source expression within the supplied
     * environment.
     *
     * <p>All profile bounds (source length, AST depth, etc.) are enforced here. The returned
     * {@link CompiledExpression} is immutable and safe to store for repeated evaluation.
     *
     * @param source the raw expression text; non-null
     * @param env    the compile-time environment describing the workflow state type and declared
     *               named-condition ids; non-null
     * @return a compiled expression ready for evaluation; never {@code null}
     * @throws ExpressionParseException if the expression is syntactically invalid, fails
     *                                  type-checking, references an undeclared identifier, or
     *                                  violates any profile bound
     */
    CompiledExpression compile(String source, ExpressionEnv env) throws ExpressionParseException;

    /**
     * Returns the stable canonical fingerprint for the given compiled expression.
     *
     * <p>The fingerprint is derived from {@link CompiledExpression#canonicalForm()} via
     * {@link ExpressionFingerprint#of(CompiledExpression)}, ensuring whitespace-only differences
     * between two source strings produce the same fingerprint (AC #8).
     *
     * @param expr the compiled expression; non-null
     * @return a 64-character lower-case SHA-256 hex string; never {@code null}
     */
    String fingerprint(CompiledExpression expr);

    /**
     * Evaluates the compiled expression against the supplied evaluation environment and returns the
     * raw result.
     *
     * <p>The {@code evalEnv} map must satisfy the environment contract described in the class
     * javadoc (a {@code "state"} entry plus one boolean entry per named-condition id).
     *
     * @param expr    the compiled expression to evaluate; non-null
     * @param evalEnv the evaluation environment map; non-null
     * @return the evaluation result; the concrete type depends on {@link CompiledExpression#resultType()}
     * @throws ExpressionEvaluationException if evaluation fails at runtime (e.g., arithmetic error,
     *                                       iteration limit exceeded, type mismatch)
     */
    Object evaluate(CompiledExpression expr, Map<String, Object> evalEnv);

    /**
     * Convenience method for predicate routing — evaluates the expression and coerces the result to
     * a primitive {@code boolean}.
     *
     * <p>The {@code evalEnv} contract is identical to {@link #evaluate}.
     *
     * @param expr    the compiled expression to evaluate; MUST have
     *                {@code resultType() == ExpressionType.BOOLEAN}
     * @param evalEnv the evaluation environment map; non-null
     * @return {@code true} or {@code false}
     * @throws IllegalStateException         if {@code expr.resultType() != BOOLEAN}
     * @throws ExpressionEvaluationException if evaluation fails at runtime
     */
    boolean evaluateBoolean(CompiledExpression expr, Map<String, Object> evalEnv);
}
