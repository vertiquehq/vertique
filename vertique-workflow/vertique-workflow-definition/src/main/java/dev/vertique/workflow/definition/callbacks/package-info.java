// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Named callback registries for the {@code vertique-workflow-definition} module.
 *
 * <p>Each registry holds a typed, named set of application-contributed callbacks that the
 * workflow-definition compiler bridges to the existing {@code WorkflowBuilder} API. The
 * registries share the same structure: an interface, a {@code Named*} record, a
 * {@code *Contributor} functional interface, and a {@code Default*Registry} singleton
 * implementation populated via Dagger multibinding.
 *
 * <p>The {@link dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup} facade
 * exposes every registry as a named accessor and provides a static
 * {@code nearestIds(unknownId, registeredIds, limit)} Levenshtein-distance utility for generating
 * "did you mean?" suggestions in validation error messages.
 */
package dev.vertique.workflow.definition.callbacks;
