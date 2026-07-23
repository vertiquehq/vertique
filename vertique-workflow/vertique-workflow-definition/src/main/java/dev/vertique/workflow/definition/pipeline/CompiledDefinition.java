// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.pipeline;

import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.util.Objects;

/**
 * The output of one successful {@link WorkflowDefinitionPipeline#load} run: a fully parsed,
 * validated, and compiled workflow definition together with its plan hash, provenance metadata,
 * and type-name strings captured at parse time.
 *
 * <p>This record is the internal storage shape used by
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionStore}. The public API surface
 * uses {@link dev.vertique.workflow.definition.service.CompiledDefinitionRef} which exposes only
 * safe, non-generic fields.
 *
 * @param definition the compiled, registry-ready workflow definition; must not be {@code null}
 * @param planHash the plan hash computed by the pipeline by building a fresh
 *     {@code WorkflowBuilder}, calling {@code definition.define(builder)}, and reading the
 *     resulting plan's hash; must not be {@code null} or blank
 * @param source provenance metadata captured from the originating
 *     {@link dev.vertique.workflow.definition.source.DefinitionResource}; must not be {@code null}
 * @param stateTypeFqn the fully-qualified class name of the workflow state type as declared in
 *     the definition document; must not be {@code null}
 * @param contractFqn the fully-qualified class name of the contract interface as declared in the
 *     definition document; must not be {@code null}
 */
public record CompiledDefinition(
        WorkflowDefinition<?, ?> definition,
        String planHash,
        SourceMetadata source,
        String stateTypeFqn,
        String contractFqn) {

    /**
     * Compact constructor: validates non-null invariants and that {@code planHash} is non-blank.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if {@code planHash} is blank
     */
    public CompiledDefinition {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(planHash, "planHash");
        if (planHash.isBlank()) {
            throw new IllegalArgumentException("planHash must not be blank");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stateTypeFqn, "stateTypeFqn");
        Objects.requireNonNull(contractFqn, "contractFqn");
    }
}
