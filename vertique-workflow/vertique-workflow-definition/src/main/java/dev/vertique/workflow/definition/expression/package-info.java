// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Replaceable expression profile SPI for workflow routing expressions.
 *
 * <p>This package defines the Vertique-owned boundary that isolates any third-party expression
 * language (EL) library from the rest of the {@code workflow-definition} module. Only the
 * concrete implementation in the {@code cel} sub-package imports third-party EL types.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionProfile} — SPI interface;
 *       all callers program to this type only</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.CompiledExpression} — opaque compiled
 *       result returned by {@code ExpressionProfile.compile(...)}</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionEnv} — compile-time
 *       environment descriptor (state type + named-condition ids)</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionType} — classifies the
 *       compile-time result type</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionFingerprint} — SHA-256
 *       fingerprint utility used by {@code DecisionRouteCompiler}</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionParseException} — thrown at
 *       compile time on parse/type-check/bound violations</li>
 *   <li>{@link dev.vertique.workflow.definition.expression.ExpressionEvaluationException} — thrown
 *       at evaluation time on runtime failures</li>
 * </ul>
 */
package dev.vertique.workflow.definition.expression;
