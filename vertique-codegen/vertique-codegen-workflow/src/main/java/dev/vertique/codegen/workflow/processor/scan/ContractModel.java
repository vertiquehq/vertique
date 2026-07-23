// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Immutable model of a scanned {@code @WorkflowContract} interface.
 *
 * <p>Produced by {@link ContractScanner} and consumed by the validator and emitter slices.
 * Contains the contract's annotation attributes and the ordered list of operation models for
 * every non-static, non-{@code Object} method declared on the interface.
 *
 * @param contractType      the {@link TypeElement} representing the {@code @WorkflowContract}
 *                          interface
 * @param definitionId      the {@code definitionId()} attribute of the annotation
 * @param definitionVersion the {@code definitionVersion()} attribute of the annotation
 * @param operations        the ordered list of {@link OperationModel}s for each method
 */
public record ContractModel(
        TypeElement contractType, String definitionId, long definitionVersion, List<OperationModel> operations) {

    /**
     * Compact constructor that copies {@code operations} defensively so the record is fully
     * immutable regardless of the mutability of the supplied list.
     *
     * @param contractType      the contract interface element; must not be {@code null}
     * @param definitionId      the definition id; must not be {@code null}
     * @param definitionVersion the definition version
     * @param operations        the mutable or immutable list of operations to copy
     */
    public ContractModel {
        operations = List.copyOf(operations);
    }
}
