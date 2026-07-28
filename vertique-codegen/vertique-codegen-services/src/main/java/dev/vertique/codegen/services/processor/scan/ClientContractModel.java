// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * The contract-only counterpart of {@link ContractModel}: the validated model for a single
 * {@code @ServiceContract} interface, ready for client-proxy emission.
 *
 * <p>Unlike {@link ContractModel} this record carries <em>no</em> implementation type — client
 * proxies are emitted per contract interface found in the compilation unit, whether or not an
 * implementation is compiled alongside it. Producing a separate record (rather than allowing a
 * {@code null} {@code implType} on {@link ContractModel}) keeps the server-side model's
 * "never null" invariant intact.
 *
 * <p>Produced by {@link ClientContractExtractor}.
 *
 * @param contractType the {@code @ServiceContract}-annotated interface; never {@code null}
 * @param operations   the client-dispatchable operations in contract-method declaration order
 *                     (static methods excluded); never {@code null}
 */
public record ClientContractModel(TypeElement contractType, List<OperationModel> operations) {}
