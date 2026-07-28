// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;

/**
 * Hand-written stand-in for a <em>stale</em> generated companion of {@link DriftContract}
 * (CG-015 §4.2 narrow unwrap, baked-id-drift fixture).
 *
 * <p>Simulates a companion jar built against an earlier interface version that declared an
 * {@code "opGhost"} operation no longer present on {@link DriftContract} — a baked-id vs.
 * runtime-entry mismatch, as opposed to a partial/incomplete entry. The constructor resolves the
 * ghost id from {@code entry.operations()} and, finding it absent, throws the same
 * {@link IllegalStateException} shape the real emitter bakes into every generated constructor —
 * the {@code create()}-time factory check must unwrap this exception directly rather than
 * wrapping it in the generic "present but could not be instantiated" message.
 */
public final class DriftContract_ServiceClientProxy implements DriftContract {

    /**
     * Looks up the stale baked operation id {@code "opGhost"}, which is never present in a
     * runtime entry built from the current {@link DriftContract} interface.
     *
     * @param sender          transport for service dispatch (unused — ctor always throws)
     * @param envelopeBuilder shared envelope builder (unused — ctor always throws)
     * @param entry           registry-resolved contract entry; operations keyed by operation id
     * @throws IllegalStateException always — {@code "opGhost"} is never present in {@code entry.operations()}
     */
    public DriftContract_ServiceClientProxy(
            ServiceRequestSender sender,
            DispatchEnvelopeBuilder envelopeBuilder,
            ServiceContractRegistry.ContractEntry<?> entry) {
        ServiceMethodMeta ghostMeta = entry.operations().get("opGhost");
        if (ghostMeta == null) {
            throw new IllegalStateException(ServiceClientFactory.CONTRACT_MISMATCH_PREFIX
                    + DriftContract.class.getName() + " has no registered operation 'opGhost' for method opGhost");
        }
        // Unreachable in this fixture — entry.operations() never contains the stale baked id.
        throw new IllegalStateException("unreachable");
    }

    @Override
    public Future<String> opA(String x) {
        return Future.succeededFuture(x);
    }
}
