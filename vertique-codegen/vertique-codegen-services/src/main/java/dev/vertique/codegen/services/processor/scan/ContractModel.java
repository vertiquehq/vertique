// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * The fully-validated model for a single {@code @ServiceContract} implementation, ready for
 * code emission.
 *
 * <p>Produced by {@link DirectImplExtractor} (for {@link ImplKind#DIRECT}) or
 * {@link HandlerImplExtractor} (for {@link ImplKind#HANDLER}) after all validators have
 * passed.
 *
 * @param contractType the {@code @ServiceContract}-annotated interface; never {@code null}
 * @param implType     the concrete implementation or handler class; never {@code null}
 * @param kind         whether this is a {@code DIRECT} or {@code HANDLER} pattern entry
 * @param operations   the list of validated operations in contract-method declaration order;
 *                     never {@code null} or empty
 */
public record ContractModel(
        TypeElement contractType, TypeElement implType, ImplKind kind, List<OperationModel> operations) {}
