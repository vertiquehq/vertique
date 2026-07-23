// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * CEL (Common Expression Language) implementation of the expression profile.
 *
 * <p><strong>Isolation contract:</strong> This sub-package is the ONLY location in the
 * {@code vertique-workflow-definition} module that may import {@code dev.cel.*} types. Every
 * other class in the module programs to the
 * {@link dev.vertique.workflow.definition.expression.ExpressionProfile} SPI interface.
 *
 * <p>Key type:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.expression.cel.CelExpressionProfile} — singleton
 *       CEL implementation; bound to {@code ExpressionProfile} by
 *       {@link dev.vertique.workflow.definition.di.WorkflowDefinitionModule}</li>
 * </ul>
 */
package dev.vertique.workflow.definition.expression.cel;
