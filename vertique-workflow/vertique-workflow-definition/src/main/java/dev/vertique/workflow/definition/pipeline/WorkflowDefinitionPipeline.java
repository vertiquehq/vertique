// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.pipeline;

import dev.vertique.workflow.definition.compiler.DocumentBackedWorkflowDefinition;
import dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionLoadException;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Orchestrates the parse → validate → compile pipeline for a single
 * {@link DefinitionResource}, producing a {@link CompiledDefinition}.
 *
 * <p>This class has no dependency on {@link dev.vertique.workflow.registry.WorkflowRegistry},
 * which is the key design decision that breaks the Dagger cycle between the bootstrap and the
 * service:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.service.WorkflowDefinitionBootstrap} uses the
 *       pipeline directly and registers compiled definitions into the
 *       {@link dev.vertique.workflow.registry.WorkflowRegistry} parameter passed via
 *       {@code WorkflowContributor.contribute(registry)}.</li>
 *   <li>{@link dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService} uses
 *       the pipeline and then calls the registry via {@code Provider<WorkflowRegistry>} (lazy
 *       resolution at first use, after the Dagger graph is fully constructed).</li>
 * </ul>
 *
 * <p>The pipeline runs these stages for each resource:
 * <ol>
 *   <li><strong>Parse</strong> — {@link WorkflowDefinitionParser#parse} deserializes the bytes
 *       into a {@link WorkflowDefinitionDocument}. A {@link WorkflowDefinitionParseException}
 *       (unknown property, unknown step type, null primitive, etc.) propagates immediately.</li>
 *   <li><strong>Resolve</strong> — a pipeline built with a type-name index resolves every contract
 *       and type name of the document through that index, in document order, before validation. The
 *       first unknown name fails the load with {@link WorkflowUnknownTypeNameException}; no class is
 *       loaded by name.</li>
 *   <li><strong>Validate</strong> — {@link WorkflowDefinitionDocumentValidator#validateOrThrow}
 *       runs all structural and semantic checks and throws a single
 *       {@link WorkflowDefinitionLoadException} carrying all accumulated violations when any
 *       fail.</li>
 *   <li><strong>Compile</strong> — {@link DocumentBackedWorkflowDefinition#fromDocument} resolves
 *       state and contract classes through the same index. A pipeline built without an index looks the
 *       classes up on the context class loader and wraps any {@link ClassNotFoundException} in a
 *       {@link WorkflowDefinitionException}.</li>
 * </ol>
 *
 * <p>After compilation, the pipeline computes the plan hash by building a fresh
 * {@link WorkflowBuilder}, calling {@code definition.define(builder)}, and reading
 * {@code plan.planHash()}. This extra builder pass is cheap (the definition is deterministic)
 * and makes the plan hash available to management surfaces ({@code WorkflowDefinitionService.list(filter)})
 * before the definition is registered.
 *
 * <p>The pipeline is stateless and thread-safe after construction.
 */
@Singleton
public final class WorkflowDefinitionPipeline {

    // --- Dependencies ---

    private final WorkflowDefinitionParser parser;
    private final WorkflowDefinitionDocumentValidator validator;
    private final WorkflowDefinitionCompiler compiler;
    private final WorkflowTypeNames typeNames;
    private final boolean classLoaderFallback;

    // --- Construction ---

    /**
     * Constructs the pipeline with all required collaborators. Type names in documents resolve by
     * class-loader lookup only.
     *
     * <p>For trusted documents only: a name is loaded as a class by name (without initialization). A
     * pipeline that serves documents from an untrusted source must use the constructor that takes the
     * type-name index, which never loads a class by name.
     *
     * @param parser the parser that deserializes raw bytes into a document; must not be
     *     {@code null}
     * @param validator the validator that accumulates and reports all document violations; must
     *     not be {@code null}
     * @param compiler the compiler that translates a validated document into builder calls; must
     *     not be {@code null}
     */
    public WorkflowDefinitionPipeline(
            WorkflowDefinitionParser parser,
            WorkflowDefinitionDocumentValidator validator,
            WorkflowDefinitionCompiler compiler) {
        this(parser, validator, compiler, new DefaultWorkflowTypeNames(), true);
    }

    /**
     * Constructs the pipeline with all required collaborators and the type-name index that resolves
     * every type and contract name of a document. A name the index does not hold fails the load with
     * {@link WorkflowUnknownTypeNameException} and no class is loaded by name.
     *
     * @param parser the parser that deserializes raw bytes into a document; must not be
     *     {@code null}
     * @param validator the validator that accumulates and reports all document violations; must
     *     not be {@code null}
     * @param compiler the compiler that translates a validated document into builder calls; must
     *     not be {@code null}
     * @param typeNames the type-name index that resolves the names; must not be {@code null}
     */
    @Inject
    public WorkflowDefinitionPipeline(
            WorkflowDefinitionParser parser,
            WorkflowDefinitionDocumentValidator validator,
            WorkflowDefinitionCompiler compiler,
            WorkflowTypeNames typeNames) {
        this(parser, validator, compiler, typeNames, false);
    }

    private WorkflowDefinitionPipeline(
            WorkflowDefinitionParser parser,
            WorkflowDefinitionDocumentValidator validator,
            WorkflowDefinitionCompiler compiler,
            WorkflowTypeNames typeNames,
            boolean classLoaderFallback) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.typeNames = Objects.requireNonNull(typeNames, "typeNames");
        this.classLoaderFallback = classLoaderFallback;
    }

    // --- Public API ---

    /**
     * Runs the full parse → validate → compile pipeline for a single definition resource.
     *
     * @param resource the raw definition resource to process; must not be {@code null}
     * @return the compiled definition; never {@code null}
     * @throws WorkflowDefinitionParseException if the bytes cannot be deserialized (Jackson
     *     parse failure, unknown step type, unknown top-level property, null primitive, etc.)
     * @throws WorkflowUnknownTypeNameException if a pipeline built with a type-name index meets a
     *     contract or type name that the index does not hold
     * @throws WorkflowDefinitionLoadException if validation reveals one or more structural or
     *     semantic violations; the exception carries all accumulated violations
     * @throws WorkflowDefinitionException if the plan build fails for any reason
     */
    public CompiledDefinition load(DefinitionResource resource) {
        return load(resource, null);
    }

    /**
     * INTERNAL framework seam — used by the definition bootstrap for packaged documents; not an
     * application contract and outside the maturity promise.
     *
     * <p>Runs the pipeline like {@link #load(DefinitionResource)}, calling {@code afterParse} with the
     * parsed document before any name is resolved. The bootstrap uses the hook to add the classes a
     * packaged, trusted document names to the type-name index.
     *
     * @param resource the raw definition resource to process; must not be {@code null}
     * @param afterParse called with the parsed document before name resolution; may be {@code null}
     * @return the compiled definition; never {@code null}
     * @throws WorkflowDefinitionParseException if the bytes cannot be deserialized
     * @throws WorkflowUnknownTypeNameException if a type or contract name of the document is not in
     *     the type-name index
     * @throws WorkflowDefinitionLoadException if validation reveals one or more violations
     * @throws WorkflowDefinitionException if compilation fails
     */
    public CompiledDefinition load(DefinitionResource resource, Consumer<WorkflowDefinitionDocument> afterParse) {
        Objects.requireNonNull(resource, "resource");

        // --- Stage 1: Parse ---
        WorkflowDefinitionDocument doc = parser.parse(resource.content(), resource.format());
        if (afterParse != null) {
            afterParse.accept(doc);
        }

        // --- Stage 2: Resolve names, then validate ---
        if (!classLoaderFallback) {
            requireKnownNames(doc);
        }
        validator.validateOrThrow(doc);

        // --- Stage 3: Compile ---
        DocumentBackedWorkflowDefinition<?, ?> definition = classLoaderFallback
                ? DocumentBackedWorkflowDefinition.fromDocument(
                        doc, compiler, Thread.currentThread().getContextClassLoader())
                : DocumentBackedWorkflowDefinition.fromDocument(doc, compiler, typeNames);

        // --- Compute plan hash eagerly ---
        String planHash = computePlanHash(definition, doc);

        return new CompiledDefinition(
                definition,
                planHash,
                resource.metadata(),
                typeNames.nameOf(definition.stateType()),
                typeNames.contractNameOf(definition.contract()));
    }

    // --- Private helpers ---

    /**
     * Resolves every contract and type name of the document through the index, in document order, and
     * fails on the first one it does not hold. No class is loaded by name.
     *
     * @param doc the parsed document
     * @throws WorkflowUnknownTypeNameException naming the document field and the bounded name
     */
    private void requireKnownNames(WorkflowDefinitionDocument doc) {
        for (DocumentTypeReferences.Reference reference : DocumentTypeReferences.of(doc)) {
            if (!reference.isKnownTo(typeNames)) {
                throw DefaultWorkflowTypeNames.unknownName(reference.field(), reference.name());
            }
        }
    }

    /**
     * Computes the plan hash by building a fresh {@link WorkflowBuilder}, driving
     * {@code definition.define(builder)}, and reading {@code plan.planHash()} from the result.
     *
     * <p>The extra builder pass is cheap because {@link WorkflowBuilder} and the DSL are
     * deterministic: the same definition always produces the same plan, and therefore the same
     * hash. Any {@link RuntimeException} thrown during this additional pass (e.g., from
     * callback validation) is wrapped in a {@link WorkflowDefinitionException}.
     *
     * @param definition the compiled definition to hash
     * @param doc the document providing the id and version for error messages
     * @return the plan hash; never {@code null} or blank
     * @throws WorkflowDefinitionException if the extra builder pass fails
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private String computePlanHash(DocumentBackedWorkflowDefinition<?, ?> definition, WorkflowDefinitionDocument doc) {
        try {
            WorkflowBuilder builder = new WorkflowBuilder<>();
            definition.define(builder);
            WorkflowPlan plan = builder.build(
                    doc.definitionId(), doc.definitionVersion(), typeNames.nameOf(definition.stateType()));
            return plan.planHash();
        } catch (WorkflowDefinitionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkflowDefinitionException(
                    "failed to compute plan hash for definition '"
                            + doc.definitionId() + "' v" + doc.definitionVersion()
                            + ": " + e.getMessage(),
                    e);
        }
    }
}
