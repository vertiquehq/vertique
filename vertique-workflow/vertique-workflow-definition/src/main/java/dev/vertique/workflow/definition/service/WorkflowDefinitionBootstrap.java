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
import dev.vertique.workflow.registry.store.WorkflowScopes;
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
 * contributions and compiles the packaged documents they supply before the application starts serving traffic.
 *
 * <p>This class implements {@link WorkflowContributor} and is exposed as one via
 * {@code @IntoSet} from {@link dev.vertique.workflow.definition.di.WorkflowDefinitionModule}.
 * It does NOT inject {@link dev.vertique.workflow.definition.service.WorkflowDefinitionService}
 * (which would create a Dagger cycle via {@code Provider<WorkflowRegistry>}).
 *
 * <p>The bootstrap registers nothing in the {@link WorkflowRegistry}: the registry holds code definitions only. It
 * keeps the compiled documents as {@link DocumentOrigin}s, which the engine's startup records in the definition
 * store; the plan of a recorded document is built from its row when it is first used.
 *
 * <h2>Bootstrap semantics</h2>
 * <p>All resources from all sources are processed through the pipeline. Successes are collected locally; failures
 * accumulate as structured {@link Violation}s. The only shared state the pass touches is the type-name index, which
 * a packaged document's classes join and which is restored when the document, or the load as a whole, is rejected.
 * Cross-resource duplicate {@code (definitionId, definitionVersion)} pairs are also detected. When every resource
 * succeeded and no duplicate was detected, the origins are retained; otherwise a single
 * {@link WorkflowDefinitionLoadException} is thrown, no origin is retained, and every name a packaged document added
 * to the type-name index is released again.
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
    private final Set<WorkflowDefinitionSource> sources;

    private final WorkflowDefinitionsConfig config;

    /** The one type-name index the packaged documents' classes join; {@code null} when none was given. */
    private final DefaultWorkflowTypeNames typeNames;

    private volatile List<DocumentOrigin> origins = List.of();

    List<DocumentOrigin> documentOrigins() {
        return origins;
    }

    // --- Construction ---

    /**
     * Constructs bootstrap with durable source retention and early typed policy validation.
     * No type-name index is given, so packaged documents add no classes to an index.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     * @param durable the durable registry store; must not be {@code null}
     * @param config the typed definition policy, when one is configured
     */
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline,
            Set<WorkflowDefinitionSource> sources,
            WorkflowDefinitionRegistryStore durable,
            Optional<WorkflowDefinitionsConfig> config) {
        this(pipeline, sources, durable, config, null);
    }

    /**
     * Constructs bootstrap with the type-name index. A packaged document is trusted: before it is
     * compiled, the bootstrap loads each class it names by binary name that the index does not hold
     * and adds it to the index, so a packaged document can name a class no catalog lists.
     *
     * @param pipeline the parse → validate → compile pipeline; must not be {@code null}
     * @param sources the set of all registered definition sources; must not be {@code null}
     * @param durable the durable registry store; must not be {@code null}
     * @param config the typed definition policy, when one is configured
     * @param typeNames the type-name index that the classes of packaged documents join; may be
     *     {@code null} to add none
     */
    @Inject
    public WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline,
            Set<WorkflowDefinitionSource> sources,
            WorkflowDefinitionRegistryStore durable,
            Optional<WorkflowDefinitionsConfig> config,
            DefaultWorkflowTypeNames typeNames) {
        this(pipeline, sources, config.orElseGet(WorkflowDefinitionsConfig::defaults), typeNames);
        Objects.requireNonNull(durable, "durable");
    }

    private WorkflowDefinitionBootstrap(
            WorkflowDefinitionPipeline pipeline,
            Set<WorkflowDefinitionSource> sources,
            WorkflowDefinitionsConfig config,
            DefaultWorkflowTypeNames typeNames) {
        this.typeNames = typeNames;
        this.config = config;
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    // --- WorkflowContributor ---

    /**
     * Iterates all registered {@link WorkflowDefinitionSource}s and compiles every resource. Nothing is registered in
     * the registry; the compiled documents are retained as {@link #documentOrigins() origins}.
     *
     * @param registry the registry the contributor was given; must not be {@code null}
     * @throws WorkflowDefinitionLoadException if any resource fails parsing, validation, or
     *     compilation, if a definition id or its source metadata exceeds the length limits, or if
     *     duplicate {@code (definitionId, definitionVersion)} pairs are detected across sources
     */
    @Override
    public void contribute(WorkflowRegistry registry) {
        Objects.requireNonNull(registry, "registry");

        if (sources.isEmpty()) {
            return;
        }

        List<CompiledDefinition> successes = new ArrayList<>();
        List<DocumentOrigin> loadedOrigins = new ArrayList<>();
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
                    // retained, so an over-bound value fails startup rather than a later record.
                    DefinitionKey key = new DefinitionKey(
                            WorkflowScopes.DEFAULT,
                            compiled.definition().definitionId(),
                            compiled.definition().definitionVersion());
                    WorkflowDefinitionRecord record = DefaultWorkflowDefinitionService.record(
                            key, compiled, resource.format().name(), pipeline.nameBinding(compiled.definition()));
                    var document = DefinitionSourceText.decode(key, resource.content(), resource.format(), config);
                    loadedOrigins.add(new DocumentOrigin(
                            key,
                            document,
                            record.provenance(),
                            compiled.planHash(),
                            compiled.contractName(),
                            compiled.stateTypeName(),
                            record.nameBinding(),
                            compiled.definition().activateOnDeploy()));
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

        // Detect (id, version) duplicates within the local successes list. Any
        // number of definitions may name one contract. A LinkedHashMap preserves insertion order for
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

        // If any failures occurred, throw a single aggregate exception — nothing is retained.
        if (!allViolations.isEmpty()) {
            indexReleases.forEach(Runnable::run);
            throw new WorkflowDefinitionLoadException(new WorkflowDefinitionViolations(allViolations));
        }

        origins = List.copyOf(loadedOrigins);
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
