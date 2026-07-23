// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of {@link WorkflowDefinitionService}.
 *
 * <p>Uses {@link jakarta.inject.Provider Provider&lt;WorkflowRegistry&gt;} (lazy resolution) to
 * break the Dagger cycle that would otherwise arise from injecting {@link WorkflowRegistry}
 * directly:
 * <ul>
 *   <li>{@link WorkflowDefinitionBootstrap} is a
 *       {@link dev.vertique.workflow.registry.WorkflowContributor} which is consumed by
 *       {@code WorkflowCoreModule.registry(...)} to construct the {@code WorkflowRegistry}.</li>
 *   <li>If {@code DefaultWorkflowDefinitionService} injected {@code WorkflowRegistry} eagerly,
 *       Dagger would detect a cycle at compile time.</li>
 *   <li>{@code Provider<WorkflowRegistry>} defers resolution to first use (after the Dagger
 *       graph is fully constructed), breaking the cycle at runtime.</li>
 * </ul>
 *
 * <h2>Concurrent activation serialization</h2>
 * <p>Concurrent calls to {@link #activate} for the same {@code (definitionId, version)} key are
 * serialized by the store's atomic CAS on the status:
 * <ol>
 *   <li>{@link WorkflowDefinitionStore#tryClaimForActivation} atomically flips
 *       {@code CANDIDATE → ACTIVATING}. Only one caller wins.</li>
 *   <li>If the status is already {@code ACTIVE}, the call returns idempotently.</li>
 *   <li>If the status is {@code SUPERSEDED}, the call throws — a higher version was already
 *       activated.</li>
 *   <li>If the status is {@code ACTIVATING} (another thread holds the claim), the call throws —
 *       the caller should retry or treat it as a conflict.</li>
 *   <li>On successful registry registration, {@link WorkflowDefinitionStore#markActive} flips
 *       {@code ACTIVATING → ACTIVE}. On failure, {@link WorkflowDefinitionStore#resetToCandidate}
 *       flips {@code ACTIVATING → CANDIDATE} so a retry can succeed.</li>
 * </ol>
 *
 * <p>Thread-safety: the store is thread-safe; the provider is thread-safe after first use.
 * Concurrent calls to {@link #load} and {@link #activate} for different keys are safe.
 *
 * <h2>Error (OOM / StackOverflow) semantics</h2>
 * <p>The activation path recovers only from {@link RuntimeException}. {@link Error} throwables
 * (e.g., {@link OutOfMemoryError}, {@link StackOverflowError}) are <em>not</em> caught, and the
 * store entry is <em>not</em> reset to {@code CANDIDATE} in that case. This is intentional:
 * <ul>
 *   <li>After an OOM or StackOverflow the JVM is typically in an unrecoverable state. Attempting
 *       to CAS the store while the heap is exhausted may itself fail.</li>
 *   <li>{@link dev.vertique.workflow.registry.DefaultWorkflowRegistry#register} is itself not
 *       Error-atomic: between its internal {@code putIfAbsent} calls an OOM can leave the registry
 *       partially populated. Resetting the store to {@code CANDIDATE} while the registry already
 *       holds the entry would produce store/registry inconsistency on any subsequent retry.</li>
 *   <li>Industry standard practice treats JVM-level {@code Error}s as terminal: the process should
 *       be restarted rather than attempting recovery.</li>
 * </ul>
 */
@Singleton
public final class DefaultWorkflowDefinitionService implements WorkflowDefinitionService {

    // --- Constants ---

    /**
     * Maximum allowed size in bytes for a workflow definition document passed to {@link #load}.
     * Documents exceeding this limit are rejected before parsing to prevent unbounded memory use
     * during deserialization (NFR-WF-DEF-006, matching the YAML parser's own code-point cap).
     */
    static final int MAX_DEFINITION_SIZE = 256 * 1024;

    // --- Dependencies ---

    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionStore store;
    private final Provider<WorkflowRegistry> registryProvider;

    // --- Construction ---

    /**
     * Constructs the service.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param store the thread-safe candidate/active store; must not be {@code null}
     * @param registryProvider lazy provider for the workflow registry (breaks the Dagger cycle);
     *     must not be {@code null}
     */
    @Inject
    public DefaultWorkflowDefinitionService(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Provider<WorkflowRegistry> registryProvider) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.store = Objects.requireNonNull(store, "store");
        this.registryProvider = Objects.requireNonNull(registryProvider, "registryProvider");
    }

    // --- WorkflowDefinitionService ---

    /**
     * {@inheritDoc}
     *
     * <p>Builds a {@link DefinitionResource} from the supplied bytes and metadata, passes it
     * through the pipeline, and stores the result as a {@link ActivationStatus#CANDIDATE}. If
     * the pipeline throws (parse failure, validation violation, class resolution failure), the
     * store is untouched and the prior active version continues to serve traffic (FR-WF-DEF-024).
     *
     * <p>Documents exceeding {@link #MAX_DEFINITION_SIZE} bytes are rejected before parsing
     * (NFR-WF-DEF-006).
     *
     * @param content the raw document bytes; must not be {@code null}
     * @param format the serialization format; must not be {@code null}
     * @param metadata provenance metadata; must not be {@code null}
     * @return a ref to the newly stored candidate; never {@code null}
     * @throws WorkflowDefinitionException if the document exceeds the size cap
     */
    @Override
    public CompiledDefinitionRef load(byte[] content, DocumentFormat format, SourceMetadata metadata) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(metadata, "metadata");

        if (content.length > MAX_DEFINITION_SIZE) {
            throw new WorkflowDefinitionException("workflow definition document exceeds size cap of "
                    + MAX_DEFINITION_SIZE + " bytes (actual: " + content.length + " bytes)");
        }

        DefinitionResource resource = new DefinitionResource(content, format, metadata);
        CompiledDefinition compiled = pipeline.load(resource);
        store.storeCandidate(compiled);

        return new CompiledDefinitionRef(
                compiled.definition().definitionId(),
                compiled.definition().definitionVersion(),
                compiled.planHash(),
                ActivationStatus.CANDIDATE,
                compiled.source());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the store entry for activation via a CAS on the status, registers it into the
     * {@link WorkflowRegistry}, and on success marks it active in the store. If registration
     * throws a {@link RuntimeException} (e.g., contract collision), the claim is released back to
     * {@link ActivationStatus#CANDIDATE} so the caller can retry. {@link Error} throwables are
     * not caught; see the class-level javadoc for the rationale.
     *
     * <p>Concurrent calls to {@code activate} for the same {@code (definitionId, version)}:
     * <ul>
     *   <li>If status is {@code ACTIVE} — returns idempotently (activation already completed).</li>
     *   <li>If status is {@code ACTIVATING} — throws; another thread holds the claim.</li>
     *   <li>If status is {@code SUPERSEDED} — throws; a higher version was already activated.</li>
     *   <li>If status is {@code CANDIDATE} — exactly one caller wins the CAS and proceeds.</li>
     * </ul>
     *
     * @param definitionId the id of the definition to activate; must not be {@code null}
     * @param definitionVersion the version to activate
     * @throws WorkflowDefinitionException if no candidate is found, if status is SUPERSEDED,
     *     if status is ACTIVATING (concurrent activation in progress), or if the registry rejects
     *     the registration
     */
    @Override
    public void activate(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");

        // tryClaimForActivation throws if the entry is not found; returns true if this caller won
        boolean claimed = store.tryClaimForActivation(definitionId, definitionVersion);

        if (!claimed) {
            // CAS did not flip CANDIDATE → ACTIVATING — inspect current status to decide outcome
            ActivationStatus current = store.currentStatus(definitionId, definitionVersion);
            if (current == ActivationStatus.ACTIVE) {
                // Another thread already completed activation — idempotent success
                return;
            }
            if (current == ActivationStatus.SUPERSEDED) {
                throw new WorkflowDefinitionException("cannot activate definition '" + definitionId + "' v"
                        + definitionVersion + ": status is SUPERSEDED; a higher version was already activated");
            }
            // ACTIVATING or CANDIDATE (race reset): another activation is in progress
            throw new WorkflowDefinitionException("cannot activate definition '" + definitionId + "' v"
                    + definitionVersion + ": activation is already in progress (status=" + current
                    + "); wait for it to complete or retry after it finishes");
        }

        // We hold the ACTIVATING claim — register into the registry.
        // On RuntimeException: reset the claim to CANDIDATE so the caller can retry.
        // On Error (OOM, StackOverflow): do NOT reset — see class-level javadoc for rationale.
        WorkflowDefinitionStore.StoredEntry entry = store.get(definitionId, definitionVersion)
                .orElseThrow(() -> new WorkflowDefinitionException("cannot activate definition '" + definitionId + "' v"
                        + definitionVersion + ": entry disappeared after claim"));
        try {
            registryProvider.get().register(entry.compiled().definition());
            // On success, transition ACTIVATING -> ACTIVE (or SUPERSEDED if a higher version raced in)
            store.markActive(definitionId, definitionVersion);
        } catch (RuntimeException e) {
            // Registration or markActive threw — release the claim so a retry can succeed.
            store.resetToCandidate(definitionId, definitionVersion);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<CompiledDefinitionRef> list() {
        return store.list();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<SourceMetadata> sourceMetadata(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        return store.sourceMetadata(definitionId, definitionVersion);
    }
}
