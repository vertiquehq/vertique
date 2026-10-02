// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the calls to a delegate source, in total and per operation id, and returns the delegate's
 * result unchanged: the very {@link OperationSchemas} instance, body provenance included.
 *
 * <p>Thread-safe: several compositions may build their mounts concurrently on different event loops.
 */
public final class CountingCanonicalSchemaSource implements OperationSchemaSource {

    private final OperationSchemaSource delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, AtomicInteger> callsByOperation = new ConcurrentHashMap<>();

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose results are returned unchanged
     */
    public CountingCanonicalSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        calls.incrementAndGet();
        callsByOperation
                .computeIfAbsent(op.operationId(), operationId -> new AtomicInteger())
                .incrementAndGet();
        return delegate.schemasFor(op, profile);
    }

    /**
     * Returns the number of calls over every operation.
     *
     * @return the call count
     */
    public int calls() {
        return calls.get();
    }

    /**
     * Returns the number of calls for one operation id.
     *
     * @param operationId the operation id
     * @return the call count for that operation, {@code 0} when never called for it
     */
    public int calls(String operationId) {
        AtomicInteger count = callsByOperation.get(operationId);
        return count == null ? 0 : count.get();
    }
}
