// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.signal;

import io.vertx.core.Future;
import java.lang.reflect.Method;

/**
 * Synthetic service contract interface for the workflow signal ingress endpoint.
 *
 * <p>This interface is NOT implemented directly by application code. {@link WorkflowSignalContributor}
 * registers it as a service contract via {@link dev.vertique.services.ServiceContractEntries} so that
 * the signal entry point is reachable over the event bus using the standard services framework dispatch
 * pipeline.
 *
 * <p>The static handle {@link #METHOD_POST} is pre-resolved at class-initialisation time for use in
 * the contributor's builder call, avoiding repeated reflective lookups at runtime.
 *
 * <p><strong>Security:</strong> this endpoint is reachable only via the internal service-dispatch
 * relay (the outbox relay invoking a {@code ServiceVerticle}), never directly by an external or
 * end-user client — it is an internal-relay-only contract. {@link WorkflowSignalRequest#metadata()}
 * is bound as the authoritative durable-context base for the signal's drive (see
 * {@link WorkflowSignalContributor#handleSignal(WorkflowSignalRequest)}) and MUST NOT be populated
 * from untrusted or end-user input — the relay is trusted to supply only carriers it produced
 * itself. Authenticating the provenance of a caller-supplied carrier at this boundary is tracked as
 * a follow-up (ADR-0147).
 */
public interface WorkflowSignalEndpoint {

    /**
     * Static reflective handle to {@link #post(WorkflowSignalRequest)}, resolved once at class
     * initialisation and used by {@link WorkflowSignalContributor} when building the contract entry.
     *
     * <p>The field is initialised via {@link #lookupPostMethod()}, which throws
     * {@link ExceptionInInitializerError} (wrapping {@link NoSuchMethodException}) if the method is
     * renamed or its signature changes, making the breakage visible immediately at startup rather than
     * silently producing a wrong dispatch target.
     */
    Method METHOD_POST = lookupPostMethod();

    /**
     * Delivers a signal to the identified workflow instance.
     *
     * @param req the signal request containing the workflow id, signal name, payload, and dedup key
     * @return a {@link Future} that completes when the signal has been applied to the workflow instance
     */
    Future<Void> post(WorkflowSignalRequest req);

    /**
     * Resolves the reflective {@link Method} handle for {@link #post(WorkflowSignalRequest)}.
     *
     * @return the resolved {@link Method}
     * @throws ExceptionInInitializerError wrapping {@link NoSuchMethodException} if the method cannot
     *     be found — indicates a broken signature change
     */
    private static Method lookupPostMethod() {
        try {
            return WorkflowSignalEndpoint.class.getMethod("post", WorkflowSignalRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
