// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Objects;

/**
 * Adapter that implements {@link WorkflowDefinition} backed by a deserialized
 * {@link WorkflowDefinitionDocument} and a {@link WorkflowDefinitionCompiler}.
 *
 * <p>Class references for the state type and contract are resolved once at factory time via
 * {@link Class#forName(String, boolean, ClassLoader)} — class <em>lookup</em> only, never
 * reflection-based instantiation (FR-WF-DEF-080). Two unchecked casts are localized to the
 * {@link #fromDocument} factory method; they are safe because the validator has already confirmed
 * that the {@code stateType} and {@code contract} FQNs in the document resolve to compatible
 * classes before the compiler is invoked.
 *
 * <p>When {@link #define(WorkflowBuilder)} is called by the registry, the adapter delegates to
 * {@link WorkflowDefinitionCompiler#emit} using a raw-typed builder, matching the same raw-cast
 * pattern used by {@code DefaultWorkflowRegistry.register()} at line 117.
 *
 * <p>This class is constructed via the static {@link #fromDocument} factory; it is not a
 * Dagger-injectable singleton because each document produces its own instance.
 *
 * @param <S> the workflow state type
 * @param <C> the workflow contract interface type
 */
public final class DocumentBackedWorkflowDefinition<S, C> implements WorkflowDefinition<S, C> {

    // --- Fields ---

    private final Class<S> stateType;
    private final Class<C> contract;
    private final WorkflowDefinitionDocument document;
    private final WorkflowDefinitionCompiler compiler;

    // --- Construction ---

    /**
     * Package-private constructor. Callers must use {@link #fromDocument}.
     *
     * @param stateType the workflow state class; non-null
     * @param contract the workflow contract interface class; non-null
     * @param document the deserialized definition document; non-null
     * @param compiler the compiler that translates the document into builder calls; non-null
     */
    DocumentBackedWorkflowDefinition(
            Class<S> stateType,
            Class<C> contract,
            WorkflowDefinitionDocument document,
            WorkflowDefinitionCompiler compiler) {
        this.stateType = Objects.requireNonNull(stateType, "stateType");
        this.contract = Objects.requireNonNull(contract, "contract");
        this.document = Objects.requireNonNull(document, "document");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    // --- WorkflowDefinition ---

    /**
     * {@inheritDoc}
     *
     * @return the workflow state class resolved from the document
     */
    @Override
    public Class<S> stateType() {
        return stateType;
    }

    /**
     * {@inheritDoc}
     *
     * @return the workflow contract interface class resolved from the document
     */
    @Override
    public Class<C> contract() {
        return contract;
    }

    /**
     * {@inheritDoc}
     *
     * @return the definition id from the document
     */
    @Override
    public String definitionId() {
        return document.definitionId();
    }

    /**
     * {@inheritDoc}
     *
     * @return the definition version from the document
     */
    @Override
    public long definitionVersion() {
        return document.definitionVersion();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link WorkflowDefinitionCompiler#emit} using a raw-typed builder. The
     * unchecked cast here mirrors the pattern used by {@code DefaultWorkflowRegistry.register()}
     * (line 117) and is safe because the validator has already confirmed type compatibility before
     * this method is called.
     *
     * @param wf the workflow builder DSL; must not be null
     */
    @Override
    public void define(WorkflowBuilder<S> wf) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        WorkflowBuilder raw = wf;
        compiler.emit(raw, stateType, contract, document);
    }

    // --- Factory ---

    /**
     * Creates a {@link DocumentBackedWorkflowDefinition} by resolving the state type and contract
     * class names declared in the document via {@link Class#forName}.
     *
     * <p>Two unchecked casts are performed: the resolved state class is cast to {@code Class<S>}
     * and the resolved contract class is cast to {@code Class<C>}. Both casts are safe at the
     * call site because the validator has already confirmed the FQNs are compatible before the
     * factory is invoked.
     *
     * @param doc the deserialized definition document; non-null
     * @param compiler the compiler to embed; non-null
     * @param cl the class loader to use for resolution; non-null
     * @return a ready-to-register {@link DocumentBackedWorkflowDefinition}; never null
     * @throws WorkflowDefinitionException if the {@code stateType} or {@code contract} class
     *     cannot be found on the given class loader
     */
    @SuppressWarnings("unchecked")
    public static DocumentBackedWorkflowDefinition<?, ?> fromDocument(
            WorkflowDefinitionDocument doc, WorkflowDefinitionCompiler compiler, ClassLoader cl) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(cl, "cl");

        Class<?> stateClass = resolveClass(doc.stateType(), cl, "stateType");
        Class<?> contractClass = resolveClass(doc.contract(), cl, "contract");
        return new DocumentBackedWorkflowDefinition<>(stateClass, contractClass, doc, compiler);
    }

    // --- Private helper ---

    /**
     * Resolves a class by name using the given class loader, wrapping any
     * {@link ClassNotFoundException} in a {@link WorkflowDefinitionException}.
     *
     * @param className the fully-qualified class name to resolve; non-null
     * @param cl the class loader to use; non-null
     * @param fieldName the field name for error context (e.g., {@code "stateType"})
     * @return the resolved class; never null
     * @throws WorkflowDefinitionException if the class is not found
     */
    private static Class<?> resolveClass(String className, ClassLoader cl, String fieldName) {
        try {
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            throw new WorkflowDefinitionException(
                    "cannot resolve " + fieldName + " class '" + className + "': " + e.getMessage(), e);
        }
    }
}
