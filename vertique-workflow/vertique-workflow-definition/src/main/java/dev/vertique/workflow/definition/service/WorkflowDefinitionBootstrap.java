// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.DocumentTypeReferences;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.definition.validator.Violation;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionViolations;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *       this phase; the only shared state it touches is the type-name index, which a packaged
 *       document's classes join and which is restored when the document, or the load as a whole, is
 *       rejected. Cross-resource duplicate {@code (definitionId, definitionVersion)} pairs and
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
 * is detected in Phase 1, a single {@link WorkflowDefinitionLoadException} is thrown, the
 * store and registry remain completely untouched, and every name a packaged document added to the
 * type-name index is released again.
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
 * {@link dev.vertique.workflow.definition.service.WorkflowDefinitionService} uses a local CAS in
 * standalone graphs and delegates durable activation to the engine's per-id publication queue.
 *
 * <h2>Parse failure attribution</h2>
 * <p>{@link WorkflowDefinitionParseException} extends {@link WorkflowDefinitionException}, so
 * parse failures are caught alongside other pipeline failures and synthesized into structured
 * violations with code {@code DOCUMENT_PARSE_FAILURE}, using the resource's URI for attribution.
 *
 * <h2>Packaged documents are trusted</h2>
 * <p>A packaged document may name a class that no catalog lists, by its binary name: before the
 * document is resolved, the bootstrap loads each class it names that the type-name index does not hold
 * and adds it to the index. A name that is not a loadable class is refused as an unknown type name and
 * reported as a {@code LOAD_FAILURE} violation attributed to the resource. When a packaged document is
 * then rejected, the names it added are released from the index.
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

    private final boolean durable;
    private final WorkflowDefinitionsConfig config;

    /** The one type-name index the packaged documents' classes join; {@code null} when none was given. */
    private final DefaultWorkflowTypeNames typeNames;

    private volatile List<DocumentOrigin> origins = List.of();

    List<DocumentOrigin> documentOrigins() {
        return origins;
    }

    // --- Construction ---

    /**
     * Constructs the bootstrap with all required collaborators.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param store the candidate/active store used to persist and activate compiled definitions;
     *     must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     */
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline, WorkflowDefinitionStore store, Set<WorkflowDefinitionSource> sources) {
        this(pipeline, store, sources, Optional.empty(), Optional.empty());
    }

    /**
     * Constructs bootstrap with optional durable source retention and early typed policy validation.
     * No type-name index is given, so packaged documents add no classes to an index.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param store the candidate/active store; must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     * @param durable the durable registry store, when one is configured
     * @param config the typed definition policy, when one is configured
     */
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Set<WorkflowDefinitionSource> sources,
            Optional<WorkflowDefinitionRegistryStore> durable,
            Optional<WorkflowDefinitionsConfig> config) {
        this(pipeline, store, sources, durable, config, null);
    }

    /**
     * Constructs bootstrap with the type-name index. A packaged document is trusted: before it is
     * compiled, the bootstrap loads each class it names by binary name that the index does not hold
     * and adds it to the index, so a packaged document can name a class no catalog lists.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param store the candidate/active store; must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     * @param durable the durable registry store, when one is configured
     * @param config the typed definition policy, when one is configured
     * @param typeNames the type-name index that the classes of packaged documents join; may be
     *     {@code null} to add none
     */
    @Inject
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline,
            WorkflowDefinitionStore store,
            Set<WorkflowDefinitionSource> sources,
            Optional<WorkflowDefinitionRegistryStore> durable,
            Optional<WorkflowDefinitionsConfig> config,
            DefaultWorkflowTypeNames typeNames) {
        this.typeNames = typeNames;
        this.durable = durable.isPresent();
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);
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
     *     compilation, if a definition id or its source metadata exceeds the length limits, or if
     *     duplicate {@code (definitionId, definitionVersion)} pairs are detected across sources
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
        List<DocumentOrigin> loadedOrigins = new ArrayList<>();
        Map<CompiledDefinition, WorkflowDefinitionRecord> records = new LinkedHashMap<>();
        // Releases the names that packaged documents added to the index; run for a rejected document.
        List<Runnable> indexReleases = new ArrayList<>();
        List<Violation> allViolations = new ArrayList<>();

        List<WorkflowDefinitionSource> orderedSources = sources.stream()
                .sorted(java.util.Comparator.comparing(s -> s.getClass().getName()))
                .toList();

        for (WorkflowDefinitionSource source : orderedSources) {
            for (DefinitionResource resource : source.resources()) {
                List<Runnable> documentReleases = new ArrayList<>();
                try {
                    CompiledDefinition compiled =
                            pipeline.load(resource, doc -> documentReleases.addAll(indexPackagedTypes(doc)));
                    // Key, provenance and record are built for every resource before anything is
                    // registered, so an over-bound value fails startup rather than a later commit.
                    DefinitionKey key = new DefinitionKey(
                            null,
                            compiled.definition().definitionId(),
                            compiled.definition().definitionVersion());
                    WorkflowDefinitionRecord record = DefaultWorkflowDefinitionService.record(
                            key, compiled, resource.format().name());
                    if (durable) {
                        var document = DefinitionSourceText.decode(key, resource.content(), resource.format(), config);
                        loadedOrigins.add(new DocumentOrigin(key, document, record.provenance(), compiled.planHash()));
                    }
                    records.put(compiled, record);
                    successes.add(compiled);
                    indexReleases.addAll(documentReleases);
                } catch (WorkflowDefinitionLoadException e) {
                    documentReleases.forEach(Runnable::run);
                    // Accumulated validation violations from one document
                    allViolations.addAll(e.violations().toList());
                } catch (IllegalArgumentException e) {
                    documentReleases.forEach(Runnable::run);
                    // Key, provenance or record bound violation — attribute it to the resource.
                    allViolations.add(new Violation(
                            "<unknown>", 0, null, null, "LOAD_FAILURE", resourceUri(resource) + ": " + e.getMessage()));
                } catch (WorkflowDefinitionException e) {
                    documentReleases.forEach(Runnable::run);
                    // Parse failure (WorkflowDefinitionParseException extends
                    // WorkflowDefinitionException) or class-resolution failure — synthesize a
                    // structured violation attributing the failure to the resource's source URI.
                    String uri = resourceUri(resource);
                    String code =
                            (e instanceof WorkflowDefinitionParseException) ? "DOCUMENT_PARSE_FAILURE" : "LOAD_FAILURE";
                    allViolations.add(new Violation("<unknown>", 0, null, null, code, uri + ": " + e.getMessage()));
                }
            }
        }

        // Detect (id, version) duplicates within the local successes list before any store mutation. Any
        // number of definitions may name one contract. Use LinkedHashMaps to preserve insertion order for
        // deterministic error messages.
        Map<String, CompiledDefinition> seenKeys = new LinkedHashMap<>();
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
        }

        // If any failures occurred, throw a single aggregate exception — store and registry
        // remain completely untouched.
        if (!allViolations.isEmpty()) {
            indexReleases.forEach(Runnable::run);
            throw new WorkflowDefinitionLoadException(new WorkflowDefinitionViolations(allViolations));
        }

        origins = List.copyOf(loadedOrigins);

        // --- Phase 2: commit — all resources succeeded, no duplicates ---
        // Each definition is stored as CANDIDATE, claimed for activation (CANDIDATE → ACTIVATING),
        // registered into the registry, then marked active (ACTIVATING → ACTIVE).
        // If registry.register() throws (e.g., a Dagger-time race with another WorkflowContributor),
        // the claim is released back to CANDIDATE and the exception propagates from contribute(),
        // causing Dagger graph construction to fail and aborting application startup.

        for (CompiledDefinition compiled : successes) {
            String defId = compiled.definition().definitionId();
            long defVersion = compiled.definition().definitionVersion();
            store.candidate(records.get(compiled), compiled);
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

    /**
     * Adds the classes a packaged document names to the index. A packaged document is trusted, so a
     * name the index does not hold is loaded as a binary class name with the context class loader
     * (lookup only, no initialization) and indexed under its own logical name. A name that is not a
     * loadable class is left for the pipeline to refuse as an unknown name.
     *
     * @return the actions that release what this call indexed; empty when nothing was indexed
     */
    private List<Runnable> indexPackagedTypes(WorkflowDefinitionDocument doc) {
        if (typeNames == null) {
            return List.of();
        }
        Class<?> contract = null;
        List<Class<?>> types = new ArrayList<>();
        for (DocumentTypeReferences.Reference reference : DocumentTypeReferences.of(doc)) {
            if (reference.isKnownTo(typeNames)) {
                continue;
            }
            Class<?> loaded = loadPackagedClass(reference.name());
            if (loaded == null) {
                continue;
            }
            if (reference.contract()) {
                contract = loaded;
            } else {
                types.add(loaded);
            }
        }
        if (contract != null || !types.isEmpty()) {
            // The classes of an accepted document stay indexed for the life of the process, like the
            // classes of a registered definition; a rejected document releases them.
            return List.of(typeNames.add(contract, types));
        }
        return List.of();
    }

    private static Class<?> loadPackagedClass(String binaryName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = WorkflowDefinitionBootstrap.class.getClassLoader();
        }
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static String resourceUri(DefinitionResource resource) {
        return resource.metadata().uri() != null
                ? resource.metadata().uri()
                : resource.metadata().sourceType();
    }
}
