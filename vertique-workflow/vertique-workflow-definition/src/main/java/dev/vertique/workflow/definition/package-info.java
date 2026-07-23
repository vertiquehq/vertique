// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Source-agnostic declarative workflow definitions.
 *
 * <p>This module loads YAML or JSON definition documents from any application-provided source
 * (file, CMS record, database row, REST upload, object store, generated artifact, etc.) and
 * compiles each document into the same canonical {@code WorkflowPlan} consumed by code-first
 * definitions. The workflow execution engine remains unaware of the source and document syntax;
 * it executes only {@code WorkflowPlan} nodes and registered callbacks.
 *
 * <p>Subpackages (added across later slices):
 * <ul>
 *   <li>{@code schema} — Jackson record tree representing a parsed definition document.
 *   <li>{@code parser} — YAML and JSON parsers backed by a dedicated {@code ObjectMapper}.
 *   <li>{@code expression} — Replaceable expression profile for safe routing predicates (CEL is the
 *       v1 candidate).
 *   <li>{@code callbacks} — Per-role named callback registries contributed by applications via
 *       Dagger multibindings.
 *   <li>{@code validator} — Load-time validator that accumulates all errors before throwing.
 *   <li>{@code compiler} — Bridge that drives the existing {@code WorkflowBuilder} from parsed
 *       documents and adapts the result into a {@code WorkflowDefinition}.
 *   <li>{@code pipeline} — Parse + validate + compile chain shared between bootstrap and runtime.
 *   <li>{@code source} — Application-provided source SPIs.
 *   <li>{@code service} — Public runtime {@code WorkflowDefinitionService} (load + activate).
 *   <li>{@code di} — Dagger module wiring.
 * </ul>
 *
 * <p>See {@code vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md}
 * for the complete format contract.
 */
package dev.vertique.workflow.definition;
