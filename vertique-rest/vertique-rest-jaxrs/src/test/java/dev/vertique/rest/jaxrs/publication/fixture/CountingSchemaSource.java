// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Test {@link OperationSchemaSource} that counts every {@link #schemasFor} call, retains the exact
 * {@link OperationSchemas} instance it returned per operation id, and delegates the actual schema
 * construction to a caller-supplied function of the descriptor and the 1-based call number (T006
 * TP-003, TP-004).
 *
 * <p>Retaining the returned instance lets a test mutate the very {@link io.vertx.core.json.JsonObject}
 * or {@link io.vertx.core.json.JsonArray} objects this source handed to the registrar, after the
 * mount has been built, to prove a capture taken earlier is a detached copy (TP-004).
 */
public final class CountingSchemaSource implements OperationSchemaSource {

    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, OperationSchemas> retained = new ConcurrentHashMap<>();
    private final BiFunction<JaxRsOperationDescriptor, Integer, OperationSchemas> factory;

    /**
     * Creates a counting source that builds each call's {@link OperationSchemas} via {@code factory}.
     *
     * @param factory given the operation descriptor and the 1-based call number, builds the schemas
     *                to return for that call
     */
    public CountingSchemaSource(BiFunction<JaxRsOperationDescriptor, Integer, OperationSchemas> factory) {
        this.factory = factory;
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        int call = calls.incrementAndGet();
        OperationSchemas schemas = factory.apply(op, call);
        retained.put(op.operationId(), schemas);
        return schemas;
    }

    /**
     * Returns the total number of {@link #schemasFor} calls so far.
     *
     * @return the call count
     */
    public int calls() {
        return calls.get();
    }

    /**
     * Returns the exact {@link OperationSchemas} instance returned for the given operation id.
     *
     * @param operationId the operation id
     * @return the retained schemas instance, or {@code null} if {@code operationId} was never asked
     */
    public OperationSchemas retained(String operationId) {
        return retained.get(operationId);
    }
}
