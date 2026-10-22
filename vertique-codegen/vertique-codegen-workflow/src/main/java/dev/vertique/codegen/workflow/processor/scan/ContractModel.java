// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Immutable model of a scanned {@code @WorkflowContract} interface.
 *
 * <p>Produced by {@link ContractScanner} and consumed by the validator and emitter slices.
 * Contains the ordered list of operation models for every non-static, non-{@code Object} method
 * declared on the interface. The model carries no definition id or version: a generated proxy
 * learns both from the client binding it is constructed with.
 *
 * @param contractType the {@link TypeElement} representing the {@code @WorkflowContract}
 *                     interface
 * @param operations   the ordered list of {@link OperationModel}s for each method
 */
public record ContractModel(TypeElement contractType, List<OperationModel> operations) {

    /**
     * Compact constructor that copies {@code operations} defensively so the record is fully
     * immutable regardless of the mutability of the supplied list.
     *
     * @param contractType the contract interface element; must not be {@code null}
     * @param operations   the mutable or immutable list of operations to copy
     */
    public ContractModel {
        operations = List.copyOf(operations);
    }
}
