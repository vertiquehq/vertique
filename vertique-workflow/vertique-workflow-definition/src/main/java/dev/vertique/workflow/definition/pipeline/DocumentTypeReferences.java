// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.pipeline;

import dev.vertique.workflow.definition.schema.HumanTaskStep;
import dev.vertique.workflow.definition.schema.StepNode;
import dev.vertique.workflow.definition.schema.WaitSignalStep;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import dev.vertique.workflow.types.WorkflowTypeNames;
import java.util.ArrayList;
import java.util.List;

/**
 * INTERNAL framework seam — shared by the definition pipeline and the bootstrap; not an application
 * contract and outside the maturity promise.
 *
 * <p>Lists the contract and type names a parsed document refers to, in the order they are resolved:
 * the contract, the state type, the start payload type, then the signal payload type of each
 * wait-for-signal step and the payload type of each human-task decision, in step order. Blank and
 * absent names are left out; the validator reports them. Step fields are labelled without the step id,
 * so a label never carries document text.
 */
public final class DocumentTypeReferences {

    private DocumentTypeReferences() {}

    /**
     * One name in a document.
     *
     * @param contract whether the name is resolved in the contract namespace; otherwise it is resolved
     *     in the type namespace
     * @param field the document field that carries the name, such as {@code stateType}
     * @param name the name as written in the document
     */
    public record Reference(boolean contract, String field, String name) {

        /**
         * Returns whether the index holds this name in the reference's namespace. Never loads a class.
         *
         * @param typeNames the index to look the name up in; must not be {@code null}
         * @return {@code true} when the index holds the name
         */
        public boolean isKnownTo(WorkflowTypeNames typeNames) {
            return contract
                    ? typeNames.findContract(name).isPresent()
                    : typeNames.find(name).isPresent();
        }
    }

    /**
     * Lists the references of a parsed document.
     *
     * @param doc the parsed document; must not be {@code null}
     * @return the references in resolution order; never {@code null}
     */
    public static List<Reference> of(WorkflowDefinitionDocument doc) {
        List<Reference> references = new ArrayList<>();
        add(references, true, "contract", doc.contract());
        add(references, false, "stateType", doc.stateType());
        add(references, false, "startPayloadType", doc.startPayloadType());
        if (doc.steps() != null) {
            for (StepNode step : doc.steps()) {
                if (step instanceof WaitSignalStep wait) {
                    add(references, false, "steps[].payloadType", wait.payloadType());
                } else if (step instanceof HumanTaskStep task && task.decisions() != null) {
                    for (HumanTaskStep.TaskDecisionBlock decision : task.decisions()) {
                        add(references, false, "steps[].decisions[].payloadType", decision.payloadType());
                    }
                }
            }
        }
        return references;
    }

    private static void add(List<Reference> references, boolean contract, String field, String name) {
        if (name != null && !name.isBlank()) {
            references.add(new Reference(contract, field, name));
        }
    }
}
