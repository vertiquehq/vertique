// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.core.async.Futures;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import java.util.Map;

/**
 * Hand-written stand-in for the generated companion of {@link SelectableContract} (CG-015 §4.1).
 *
 * <p>Mimics what {@code ClientProxyEmitter} will emit: a {@code public final} class implementing
 * the contract, the frozen 3-arg constructor, per-operation metadata resolved eagerly at
 * construction (a missing operation fails loudly with the §4.2-pinned mismatch prefix), and
 * dispatch delegated to {@link ServiceRequestSender}. Used by
 * {@link ServiceClientFactoryCompanionTest} to prove companion selection before the real
 * emitter/seam exist.
 *
 * <p>{@code ServiceClientProxyParityTest} (codegen-services) independently proves that the real
 * emitter's generated companion output behaves identically to this hand-written stand-in.
 */
public final class SelectableContract_ServiceClientProxy implements SelectableContract {

    private final ServiceRequestSender sender;
    private final DispatchEnvelopeBuilder envelopeBuilder;
    private final ResolvedServiceTarget pingTarget;

    /**
     * Resolves the {@code "ping"} operation metadata from the supplied entry.
     *
     * @param sender          transport for service dispatch (supervisor check, timeouts, enrichment)
     * @param envelopeBuilder shared envelope builder (MDC + dispatch-context capture)
     * @param entry           registry-resolved contract entry; operations keyed by operation id
     * @throws IllegalStateException if {@code "ping"} is missing from {@code entry.operations()}
     */
    public SelectableContract_ServiceClientProxy(
            ServiceRequestSender sender,
            DispatchEnvelopeBuilder envelopeBuilder,
            ServiceContractRegistry.ContractEntry<?> entry) {
        this.sender = sender;
        this.envelopeBuilder = envelopeBuilder;
        ServiceMethodMeta pingMeta = entry.operations().get("ping");
        if (pingMeta == null) {
            throw new IllegalStateException(ServiceClientFactory.CONTRACT_MISMATCH_PREFIX
                    + SelectableContract.class.getName() + " has no registered operation 'ping' for method ping");
        }
        this.pingTarget = ResolvedServiceTarget.of(SelectableContract.class, pingMeta);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Future<String> ping(String x) {
        DispatchEnvelope<?> envelope = envelopeBuilder.build(x, Map.of(), DispatchBoundary.SERVICE_DISPATCH);
        Future<Result<String>> typed = (Future<Result<String>>) (Future<?>) sender.send(pingTarget, envelope);
        return typed.compose(Futures::toFuture);
    }

    @Override
    public String toString() {
        return "ServiceProxy[SelectableContract]";
    }
}
