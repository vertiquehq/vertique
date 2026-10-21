// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.compiler;

import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.util.Objects;

/**
 * Adapter that implements {@link WorkflowDefinition} backed by a deserialized
 * {@link WorkflowDefinitionDocument} and a {@link WorkflowDefinitionCompiler}.
 *
 * <p>Class references for the state type and contract are resolved once at factory time, through a
 * type-name index or, for callers without one, by class <em>lookup</em> on a class loader — never by
 * reflection-based instantiation. Two unchecked casts are localized to the
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
     * @return the document's {@code activateOnDeploy} header, {@code true} when the document has none
     */
    @Override
    public boolean activateOnDeploy() {
        return document.activateOnDeploy();
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
     * <p>Names resolve by class-loader lookup only; this overload is for tests and other callers that
     * build a definition from a trusted document without a type-name index. It loads a class by name
     * (without initialization), so it must not be given a document from an untrusted source; use the
     * overload that takes the type-name index for those.
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
        Class<?> stateClass = loadClass(doc.stateType(), cl, "stateType");
        Class<?> contractClass = loadClass(doc.contract(), cl, "contract");
        return new DocumentBackedWorkflowDefinition<>(stateClass, contractClass, doc, compiler);
    }

    /**
     * Creates a {@link DocumentBackedWorkflowDefinition} by resolving the state type and contract
     * names declared in the document through a type-name index only.
     *
     * <p>The state name is looked up in the type namespace of {@code typeNames} and the contract name
     * in its contract namespace. A name the index does not know fails with
     * {@link WorkflowUnknownTypeNameException}; no class is loaded by name.
     *
     * <p>Both three-argument overloads of this method reject {@code null}; a literal {@code null} for
     * the third argument is ambiguous and does not compile without a cast.
     *
     * <p>Two unchecked casts are performed: the resolved state class is cast to {@code Class<S>}
     * and the resolved contract class is cast to {@code Class<C>}. Both casts are safe at the
     * call site because the validator has already confirmed the names are compatible before the
     * factory is invoked.
     *
     * @param doc the deserialized definition document; non-null
     * @param compiler the compiler to embed; non-null
     * @param typeNames the type-name index that resolves the names; non-null
     * @return a ready-to-register {@link DocumentBackedWorkflowDefinition}; never null
     * @throws WorkflowUnknownTypeNameException if the {@code stateType} or {@code contract} name is
     *     not in the index
     */
    @SuppressWarnings("unchecked")
    public static DocumentBackedWorkflowDefinition<?, ?> fromDocument(
            WorkflowDefinitionDocument doc, WorkflowDefinitionCompiler compiler, WorkflowTypeNames typeNames) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(typeNames, "typeNames");

        Class<?> stateClass = typeNames
                .find(doc.stateType())
                .orElseThrow(() -> DefaultWorkflowTypeNames.unknownName("stateType", doc.stateType()));
        Class<?> contractClass = typeNames
                .findContract(doc.contract())
                .orElseThrow(() -> DefaultWorkflowTypeNames.unknownName("contract", doc.contract()));
        return new DocumentBackedWorkflowDefinition<>(stateClass, contractClass, doc, compiler);
    }

    // --- Private helper ---

    /**
     * Resolves the name using the given class loader, wrapping any {@link ClassNotFoundException} in
     * a {@link WorkflowDefinitionException}.
     *
     * @param className the fully-qualified class name to resolve; non-null
     * @param cl the class loader to use; non-null
     * @param fieldName the field name for error context (e.g., {@code "stateType"})
     * @return the resolved class; never null
     * @throws WorkflowDefinitionException if the class is not found
     */
    private static Class<?> loadClass(String className, ClassLoader cl, String fieldName) {
        try {
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            throw new WorkflowDefinitionException(
                    "cannot resolve " + fieldName + " class '" + className + "': " + e.getMessage(), e);
        }
    }
}
