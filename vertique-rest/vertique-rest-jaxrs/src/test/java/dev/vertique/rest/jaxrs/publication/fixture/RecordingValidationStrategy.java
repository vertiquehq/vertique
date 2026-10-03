// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link RequestValidationStrategy} selected by {@link #ID} that stores a deep copy of every
 * {@link OperationSchemas} it receives in {@link #gateFor}, keyed by operation id, and always
 * installs a pass-through gate (T006 TP-003, TP-005).
 */
public final class RecordingValidationStrategy implements RequestValidationStrategy {

    /** The selection id this strategy registers under. */
    public static final String ID = "recording-strategy";

    private final Map<String, OperationSchemas> received = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        received.put(op.operationId(), deepCopy(op, schemas));
        return Optional.of(RoutingContext::next);
    }

    /**
     * Returns the deep copy recorded for the given operation id.
     *
     * @param operationId the operation id
     * @return the recorded schemas copy, or {@code null} if {@code operationId} was never gated
     */
    public OperationSchemas received(String operationId) {
        return received.get(operationId);
    }

    private static OperationSchemas deepCopy(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        OperationSchemas.Builder builder = OperationSchemas.builder();
        schemas.bodySchema().ifPresent(body -> {
            Object provenance = schemas.bodySchemaProvenance(Object.class).orElse(null);
            if (provenance != null) {
                builder.bodySchema(body.copy(), provenance);
            } else {
                builder.bodySchema(body.copy());
            }
        });
        for (ParamDescriptor param : op.parameters()) {
            schemas.parameterSchema(param.location(), param.name())
                    .ifPresent(schema -> builder.parameterSchema(param.location(), param.name(), schema.copy()));
        }
        return builder.build();
    }
}
