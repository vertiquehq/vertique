// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.context.DispatchEnvelopeBuilder;
import io.vertx.core.Future;

/**
 * Hand-written broken stand-in for the generated companion of {@link BrokenContract}.
 *
 * <p>Present on the test classpath with the <em>correct</em> §4.1 constructor signature so that
 * {@link dev.vertique.core.util.GeneratedCompanions#instantiate} attempts reflective construction,
 * but the constructor body unconditionally throws an unrelated {@link RuntimeException} — the
 * present-but-broken case that must fail {@code create()} loudly (§4.2 {@code brokenCompanion},
 * wide-fallback branch) rather than silently degrading to the JDK dynamic proxy.
 */
public final class BrokenContract_ServiceClientProxy implements BrokenContract {

    /**
     * Intentionally broken — always throws so {@code GeneratedCompanions.instantiate} triggers the
     * present-but-broken (loud-fail) path.
     *
     * @param sender          transport for service dispatch (unused — ctor always throws)
     * @param envelopeBuilder shared envelope builder (unused — ctor always throws)
     * @param entry           registry-resolved contract entry (unused — ctor always throws)
     */
    public BrokenContract_ServiceClientProxy(
            ServiceRequestSender sender,
            DispatchEnvelopeBuilder envelopeBuilder,
            ServiceContractRegistry.ContractEntry<?> entry) {
        throw new RuntimeException("fixture ctor boom");
    }

    @Override
    public Future<String> ping(String x) {
        return Future.succeededFuture(x);
    }
}
