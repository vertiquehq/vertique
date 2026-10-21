// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;

/**
 * Reads the logical name of a {@code @WorkflowContract} interface or a {@code @WorkflowType} class
 * into a {@link Entry} of the generated type catalog.
 *
 * <p>The contract name is the non-empty {@code value()} of the annotation, else the binary class
 * name, matching the name the runtime index gives the same interface. The type name is the
 * {@code value()} of {@code @WorkflowType}.
 */
public final class TypeCatalogScanner {

    private static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";
    private static final String WORKFLOW_TYPE_FQN = "dev.vertique.workflow.types.WorkflowType";
    private static final String ATTR_VALUE = "value";

    /** The namespace of a catalog entry; the declaration order is the order entries are emitted in. */
    public enum Kind {
        /** A {@code @WorkflowContract} interface. */
        CONTRACT,
        /** A {@code @WorkflowType} class. */
        TYPE
    }

    /**
     * One class that the generated catalog lists.
     *
     * @param kind the namespace of the name
     * @param name the logical name
     * @param type the annotated class
     */
    public record Entry(Kind kind, String name, TypeElement type) {}

    private final CodegenContext ctx;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public TypeCatalogScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the catalog entry of a contract interface.
     *
     * @param contract the interface carrying {@code @WorkflowContract}; must not be {@code null}
     * @return the CONTRACT entry named by the annotation value, or by the binary name when the value
     *     is empty
     */
    public Entry contractEntry(TypeElement contract) {
        String value = AnnotationMirrors.findByFqn(contract, WORKFLOW_CONTRACT_FQN)
                .flatMap(mirror -> ctx.annotations().attribute(mirror, ATTR_VALUE, String.class))
                .orElse("");
        String name = value.isEmpty() ? ctx.elements().getBinaryName(contract).toString() : value;
        return new Entry(Kind.CONTRACT, name, contract);
    }

    /**
     * Returns the catalog entry of a {@code @WorkflowType} class.
     *
     * @param type the class carrying {@code @WorkflowType}; must not be {@code null}
     * @return the TYPE entry named by the annotation value, or empty when the annotation has no
     *     readable value (the compiler reports that itself)
     */
    public Optional<Entry> typeEntry(TypeElement type) {
        Optional<AnnotationMirror> mirror = AnnotationMirrors.findByFqn(type, WORKFLOW_TYPE_FQN);
        return mirror.flatMap(m -> ctx.annotations().attribute(m, ATTR_VALUE, String.class))
                .map(name -> new Entry(Kind.TYPE, name, type));
    }
}
