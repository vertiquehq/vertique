// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.pipeline;

import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import java.util.Objects;

/**
 * The output of one successful {@link WorkflowDefinitionPipeline#load} run: a fully parsed,
 * validated, and compiled workflow definition together with its plan hash, provenance metadata,
 * and the logical names of its state type and contract.
 *
 * <p>This record is the internal compilation result of the pipeline. The public API surface
 * uses {@link dev.vertique.workflow.definition.service.CompiledDefinitionRef} which exposes only
 * safe, non-generic fields.
 *
 * @param definition the compiled, registry-ready workflow definition; must not be {@code null}
 * @param planHash the plan hash computed by the pipeline by building a fresh
 *     {@code WorkflowBuilder}, calling {@code definition.define(builder)}, and reading the
 *     resulting plan's hash; must not be {@code null} or blank
 * @param source provenance metadata captured from the originating
 *     {@link dev.vertique.workflow.definition.source.DefinitionResource}; must not be {@code null}
 * @param stateTypeName the logical name of the workflow state type, as the type-name index reports
 *     it for the resolved state class; must not be {@code null}
 * @param contractName the logical name of the contract interface, as the type-name index reports it
 *     for the resolved contract class; must not be {@code null}
 */
public record CompiledDefinition(
        WorkflowDefinition<?, ?> definition,
        String planHash,
        SourceMetadata source,
        String stateTypeName,
        String contractName) {

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
        Objects.requireNonNull(stateTypeName, "stateTypeName");
        Objects.requireNonNull(contractName, "contractName");
    }
}
