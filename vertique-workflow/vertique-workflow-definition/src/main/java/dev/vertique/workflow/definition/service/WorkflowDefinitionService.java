// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import java.util.Collection;
import java.util.Optional;

/**
 * Runtime SPI for loading, activating, and querying document-backed workflow definitions.
 *
 * <p>This service manages the lifecycle of a document-backed definition from upload through
 * activation:
 * <ol>
 *   <li><strong>Load</strong> — parse, validate, and compile a definition document. The result
 *       is stored in the {@link WorkflowDefinitionStore} as a {@link ActivationStatus#CANDIDATE}
 *       but is NOT yet registered into the
 *       {@link dev.vertique.workflow.registry.WorkflowRegistry}. The prior active version
 *       continues to serve new workflow instances.</li>
 *   <li><strong>Activate</strong> — register a previously loaded candidate into the registry.
 *       After activation, new workflow instances will start on this version. Prior active
 *       versions for the same {@code definitionId} become {@link ActivationStatus#SUPERSEDED} in
 *       the store but remain pinnable for in-flight instances (FR-WF-DEF-061).</li>
 * </ol>
 *
 * <p>Error handling:
 * <ul>
 *   <li>A load failure (parse error, validation violation, class resolution failure) leaves the
 *       store untouched — the prior active version continues to serve traffic (FR-WF-DEF-024).</li>
 *   <li>An activation failure (e.g., contract collision in the registry) leaves the candidate
 *       as {@link ActivationStatus#CANDIDATE} in the store; the failed registration is rolled
 *       back atomically by {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry}.</li>
 * </ul>
 */
public interface WorkflowDefinitionService {

    /**
     * Parses, validates, and compiles a workflow definition document, storing the result as a
     * {@link ActivationStatus#CANDIDATE} in the {@link WorkflowDefinitionStore}.
     *
     * <p>The definition is NOT registered into the
     * {@link dev.vertique.workflow.registry.WorkflowRegistry} by this method. Call
     * {@link #activate(String, long)} to promote it.
     *
     * @param content the raw bytes of the definition document (YAML or JSON); must not be
     *     {@code null}
     * @param format the serialization format of {@code content}; must not be {@code null}
     * @param metadata provenance metadata to store alongside the definition; must not be
     *     {@code null}
     * @return a reference to the stored candidate; never {@code null}
     * @throws dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException if the
     *     bytes cannot be deserialized
     * @throws dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException if
     *     validation reveals one or more violations
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if class resolution or
     *     plan building fails, or if the {@code (definitionId, definitionVersion)} is already
     *     stored in the store
     */
    CompiledDefinitionRef load(byte[] content, DocumentFormat format, SourceMetadata metadata);

    /**
     * Registers a previously loaded candidate into the
     * {@link dev.vertique.workflow.registry.WorkflowRegistry}, transitioning it from
     * {@link ActivationStatus#CANDIDATE} to {@link ActivationStatus#ACTIVE}.
     *
     * <p>Prior active versions for the same {@code definitionId} are transitioned to
     * {@link ActivationStatus#SUPERSEDED} in the store (informational only — they remain
     * resolvable by pinned version in the registry per FR-WF-DEF-061).
     *
     * @param definitionId the id of the definition to activate; must not be {@code null}
     * @param definitionVersion the version to activate; must match a stored candidate
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if no candidate with the
     *     given id/version is found in the store, or if the candidate is already superseded
     * @throws dev.vertique.workflow.exception.WorkflowDefinitionException if the registry rejects
     *     the registration (e.g., contract collision with a previously registered definition)
     */
    void activate(String definitionId, long definitionVersion);

    /**
     * Returns a snapshot of all definitions currently in the store, across all statuses.
     *
     * @return an immutable collection of refs; never {@code null}; may be empty
     */
    Collection<CompiledDefinitionRef> list();

    /**
     * Returns the provenance metadata for the stored definition with the given id and version,
     * or {@link Optional#empty()} if not found.
     *
     * @param definitionId the definition id to look up; must not be {@code null}
     * @param definitionVersion the version to look up
     * @return the stored metadata, or empty if the {@code (id, version)} pair is not in the store
     */
    Optional<SourceMetadata> sourceMetadata(String definitionId, long definitionVersion);
}
