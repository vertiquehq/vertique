// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.definition.validator.Violation;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionViolations;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Startup-time {@link WorkflowContributor} that iterates all {@link WorkflowDefinitionSource}
 * contributions and loads them into the {@link WorkflowRegistry} before the application starts
 * serving traffic.
 *
 * <p>This class implements {@link WorkflowContributor} and is exposed as one via
 * {@code @IntoSet} from {@link dev.vertique.workflow.definition.di.WorkflowDefinitionModule}.
 * It does NOT inject {@link dev.vertique.workflow.definition.service.WorkflowDefinitionService}
 * (which would create a Dagger cycle via {@code Provider<WorkflowRegistry>}); instead it works
 * directly with the {@code registry} parameter passed by
 * {@code WorkflowContributor.contribute(registry)}.
 *
 * <h2>Bootstrap semantics</h2>
 * <p>The bootstrap executes in two strictly separated phases:
 * <ol>
 *   <li><strong>Parse + validate + compile phase</strong> — all resources from all sources are
 *       processed through the pipeline. Successes are collected locally; failures accumulate as
 *       structured {@link Violation}s. The store and registry are <em>not</em> mutated during
 *       this phase. Cross-resource duplicate {@code (definitionId, definitionVersion)} pairs and
 *       duplicate contract classes are also detected in this phase.</li>
 *   <li><strong>Commit phase</strong> — executed only when every resource succeeded and no
 *       cross-source duplicates were detected. Each compiled definition is stored as a candidate,
 *       claimed atomically for activation, registered into the registry, and marked active —
 *       one definition at a time.</li>
 * </ol>
 *
 * <h2>All-or-nothing guarantee and residual risks</h2>
 * <p>Phase 1 guarantees that no store or registry mutation is attempted unless all resources are
 * individually valid and have no cross-resource conflicts. If any resource fails or any conflict
 * is detected in Phase 1, a single {@link WorkflowDefinitionLoadException} is thrown and the
 * store and registry remain completely untouched.
 *
 * <p>Phase 2 commits definitions to the registry <em>one at a time</em>. If a registration throws
 * a {@link RuntimeException} after the Phase 1 pre-flight checks pass (e.g., a Dagger-time race
 * with another {@link WorkflowContributor} that contributed the same definition via the code-based
 * multibinding), the activation claim is released back to {@link ActivationStatus#CANDIDATE} and
 * the exception propagates from {@link #contribute}. Dagger graph construction fails — application
 * startup aborts. The store may contain a partial set of definitions at that point, but application
 * startup does not reach the serving phase so no inconsistent state is exposed to callers.
 *
 * <h2>Error (OOM / StackOverflow) semantics</h2>
 * <p>Phase 2 recovers only from {@link RuntimeException}. {@link Error} throwables (e.g.,
 * {@link OutOfMemoryError}, {@link StackOverflowError}) are not caught and the ACTIVATING claim is
 * not reset. This is intentional: the JVM is typically unrecoverable after such errors, and
 * {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry#register} is itself not
 * Error-atomic — attempting a store reset while the heap is exhausted can produce
 * store/registry inconsistency that is worse than the leaked claim. The process should be
 * restarted rather than attempting recovery from JVM-level {@code Error}s.
 *
 * <p>Runtime activation through
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionService} has its own per-key
 * CAS serialization and is not affected by this startup guarantee.
 *
 * <h2>Parse failure attribution</h2>
 * <p>{@link WorkflowDefinitionParseException} extends {@link WorkflowDefinitionException}, so
 * parse failures are caught alongside other pipeline failures and synthesized into structured
 * violations with code {@code DOCUMENT_PARSE_FAILURE}, using the resource's URI for attribution.
 *
 * <h2>Source ordering</h2>
 * <p>Sources are iterated in ascending order of their implementation class name
 * ({@link Class#getName()}). Within each source, resources are processed in the order returned
 * by {@link WorkflowDefinitionSource#resources()}. This ordering is deterministic across JVM
 * restarts and ensures that duplicate-detection error messages are reproducible.
 */
@Singleton
public final class WorkflowDefinitionBootstrap implements WorkflowContributor {

    // --- Dependencies ---

    private final WorkflowDefinitionPipeline pipeline;
    private final WorkflowDefinitionStore store;
    private final Set<WorkflowDefinitionSource> sources;

    // --- Construction ---

    /**
     * Constructs the bootstrap with all required collaborators.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param store the candidate/active store used to persist and activate compiled definitions;
     *     must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     */
    @Inject
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline, WorkflowDefinitionStore store, Set<WorkflowDefinitionSource> sources) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.store = Objects.requireNonNull(store, "store");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    // --- WorkflowContributor ---

    /**
     * Iterates all registered {@link WorkflowDefinitionSource}s, compiles every resource, and
     * registers the results into the provided {@link WorkflowRegistry}.
     *
     * <p>The operation is all-or-nothing across two phases:
     * <ol>
     *   <li>Parse + validate + compile every resource. Failures are accumulated as structured
     *       violations; successes are held locally. The store and registry are not mutated.</li>
     *   <li>If all resources succeeded and no cross-source duplicates were found, store each
     *       compiled definition as a candidate, register it, and mark it active.</li>
     * </ol>
     *
     * @param registry the registry to register definitions into; must not be {@code null}
     * @throws WorkflowDefinitionLoadException if any resource fails parsing, validation, or
     *     compilation, or if duplicate {@code (definitionId, definitionVersion)} pairs are
     *     detected across sources
     * @throws WorkflowDefinitionException if a registry registration fails during Phase 2 (after
     *     all resources successfully compiled); the failed entry's ACTIVATING claim is released
     *     back to CANDIDATE before rethrowing
     */
    @Override
    public void contribute(WorkflowRegistry registry) {
        Objects.requireNonNull(registry, "registry");

        if (sources.isEmpty()) {
            return;
        }

        // --- Phase 1: parse + validate + compile — no store or registry mutations ---

        List<CompiledDefinition> successes = new ArrayList<>();
        List<Violation> allViolations = new ArrayList<>();

        List<WorkflowDefinitionSource> orderedSources = sources.stream()
                .sorted(java.util.Comparator.comparing(s -> s.getClass().getName()))
                .toList();

        for (WorkflowDefinitionSource source : orderedSources) {
            for (DefinitionResource resource : source.resources()) {
                try {
                    CompiledDefinition compiled = pipeline.load(resource);
                    successes.add(compiled);
                } catch (WorkflowDefinitionLoadException e) {
                    // Accumulated validation violations from one document
                    allViolations.addAll(e.violations().toList());
                } catch (WorkflowDefinitionException e) {
                    // Parse failure (WorkflowDefinitionParseException extends
                    // WorkflowDefinitionException) or class-resolution failure — synthesize a
                    // structured violation attributing the failure to the resource's source URI.
                    String uri = resource.metadata().uri() != null
                            ? resource.metadata().uri()
                            : resource.metadata().sourceType();
                    String code =
                            (e instanceof WorkflowDefinitionParseException) ? "DOCUMENT_PARSE_FAILURE" : "LOAD_FAILURE";
                    allViolations.add(new Violation("<unknown>", 0, null, null, code, uri + ": " + e.getMessage()));
                }
            }
        }

        // Detect (id, version) duplicates and contract-class duplicates within the local successes
        // list before any store mutation. Use LinkedHashMaps to preserve insertion order for
        // deterministic error messages.
        Map<String, CompiledDefinition> seenKeys = new LinkedHashMap<>();
        Map<String, CompiledDefinition> seenContracts = new LinkedHashMap<>();
        for (CompiledDefinition compiled : successes) {
            String defId = compiled.definition().definitionId();
            long defVersion = compiled.definition().definitionVersion();

            // (id, version) duplicate detection
            String idVersionKey = defId + "|" + defVersion;
            CompiledDefinition priorById = seenKeys.putIfAbsent(idVersionKey, compiled);
            if (priorById != null) {
                allViolations.add(new Violation(
                        defId,
                        defVersion,
                        null,
                        null,
                        "DUPLICATE_DEFINITION",
                        "definition '" + defId + "' v" + defVersion
                                + " appears more than once across registered sources"));
            }

            // Contract class duplicate detection — one contract class may only map to one
            // (definitionId, definitionVersion) pair. A collision here would cause a runtime
            // exception when the registry resolves contract metadata.
            String contractFqn = compiled.contractFqn();
            CompiledDefinition priorByContract = seenContracts.putIfAbsent(contractFqn, compiled);
            if (priorByContract != null) {
                String priorDefId = priorByContract.definition().definitionId();
                long priorDefVersion = priorByContract.definition().definitionVersion();
                allViolations.add(new Violation(
                        defId,
                        defVersion,
                        null,
                        null,
                        "DUPLICATE_CONTRACT",
                        "contract class '" + contractFqn + "' for definition '" + defId + "' v" + defVersion
                                + " is already bound to definition '" + priorDefId + "' v" + priorDefVersion));
            }
        }

        // If any failures occurred, throw a single aggregate exception — store and registry
        // remain completely untouched.
        if (!allViolations.isEmpty()) {
            throw new WorkflowDefinitionLoadException(new WorkflowDefinitionViolations(allViolations));
        }

        // --- Phase 2: commit — all resources succeeded, no duplicates ---
        // Each definition is stored as CANDIDATE, claimed for activation (CANDIDATE → ACTIVATING),
        // registered into the registry, then marked active (ACTIVATING → ACTIVE).
        // If registry.register() throws (e.g., a Dagger-time race with another WorkflowContributor),
        // the claim is released back to CANDIDATE and the exception propagates from contribute(),
        // causing Dagger graph construction to fail and aborting application startup.

        for (CompiledDefinition compiled : successes) {
            String defId = compiled.definition().definitionId();
            long defVersion = compiled.definition().definitionVersion();
            store.storeCandidate(compiled);
            boolean claimed = store.tryClaimForActivation(defId, defVersion);
            if (!claimed) {
                // Should not happen during bootstrap (no concurrent activations before startup),
                // but guard defensively.
                throw new WorkflowDefinitionException("failed to claim definition '" + defId + "' v" + defVersion
                        + " for activation during bootstrap");
            }
            // On RuntimeException: reset the claim to CANDIDATE so the failure is diagnosable.
            // On Error (OOM, StackOverflow): do NOT reset — see class-level javadoc for rationale.
            try {
                registry.register(compiled.definition());
                store.markActive(defId, defVersion);
            } catch (RuntimeException e) {
                store.resetToCandidate(defId, defVersion);
                throw e;
            }
        }
    }
}
