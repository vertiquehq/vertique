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
import dev.vertique.workflow.plan.WorkflowPlan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

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
 * <p>The pipeline runs three stages for each resource:
 * <ol>
 *   <li><strong>Parse</strong> — {@link WorkflowDefinitionParser#parse} deserializes the bytes
 *       into a {@link WorkflowDefinitionDocument}. A {@link WorkflowDefinitionParseException}
 *       (unknown property, unknown step type, null primitive, etc.) propagates immediately.</li>
 *   <li><strong>Validate</strong> — {@link WorkflowDefinitionDocumentValidator#validateOrThrow}
 *       runs all structural and semantic checks and throws a single
 *       {@link WorkflowDefinitionLoadException} carrying all accumulated violations when any
 *       fail.</li>
 *   <li><strong>Compile</strong> — {@link DocumentBackedWorkflowDefinition#fromDocument} resolves
 *       state and contract classes. Any {@link ClassNotFoundException} is wrapped in a
 *       {@link WorkflowDefinitionException}.</li>
 * </ol>
 *
 * <p>After compilation, the pipeline computes the plan hash by building a fresh
 * {@link WorkflowBuilder}, calling {@code definition.define(builder)}, and reading
 * {@code plan.planHash()}. This extra builder pass is cheap (the definition is deterministic)
 * and makes the plan hash available to management surfaces ({@code WorkflowDefinitionService.list()})
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

    // --- Construction ---

    /**
     * Constructs the pipeline with all required collaborators.
     *
     * @param parser the parser that deserializes raw bytes into a document; must not be
     *     {@code null}
     * @param validator the validator that accumulates and reports all document violations; must
     *     not be {@code null}
     * @param compiler the compiler that translates a validated document into builder calls; must
     *     not be {@code null}
     */
    @Inject
    public WorkflowDefinitionPipeline(
            WorkflowDefinitionParser parser,
            WorkflowDefinitionDocumentValidator validator,
            WorkflowDefinitionCompiler compiler) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    // --- Public API ---

    /**
     * Runs the full parse → validate → compile pipeline for a single definition resource.
     *
     * @param resource the raw definition resource to process; must not be {@code null}
     * @return the compiled definition; never {@code null}
     * @throws WorkflowDefinitionParseException if the bytes cannot be deserialized (Jackson
     *     parse failure, unknown step type, unknown top-level property, null primitive, etc.)
     * @throws WorkflowDefinitionLoadException if validation reveals one or more structural or
     *     semantic violations; the exception carries all accumulated violations
     * @throws WorkflowDefinitionException if the state type or contract class cannot be resolved
     *     on the class loader, or if the plan build fails for any reason
     */
    public CompiledDefinition load(DefinitionResource resource) {
        Objects.requireNonNull(resource, "resource");

        // --- Stage 1: Parse ---
        WorkflowDefinitionDocument doc = parser.parse(resource.content(), resource.format());

        // --- Stage 2: Validate ---
        validator.validateOrThrow(doc);

        // --- Stage 3: Compile ---
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        DocumentBackedWorkflowDefinition<?, ?> definition =
                DocumentBackedWorkflowDefinition.fromDocument(doc, compiler, cl);

        // --- Compute plan hash eagerly ---
        String planHash = computePlanHash(definition, doc);

        return new CompiledDefinition(definition, planHash, resource.metadata(), doc.stateType(), doc.contract());
    }

    // --- Private helpers ---

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
    private static String computePlanHash(
            DocumentBackedWorkflowDefinition<?, ?> definition, WorkflowDefinitionDocument doc) {
        try {
            WorkflowBuilder builder = new WorkflowBuilder<>();
            definition.define(builder);
            WorkflowPlan plan = builder.build(doc.definitionId(), doc.definitionVersion(), doc.stateType());
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
