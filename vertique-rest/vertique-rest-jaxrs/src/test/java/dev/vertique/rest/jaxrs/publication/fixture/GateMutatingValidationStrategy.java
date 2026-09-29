// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test {@link RequestValidationStrategy} selected by {@link #ID} that, inside {@link #gateFor}, adds
 * {@code "x-gate": true} to every {@link JsonObject} reachable from the received
 * {@link OperationSchemas} — the body schema, its nested objects and arrays, and every declared
 * parameter's schema — keeps a reference to the (now-mutated) schemas it received, and always
 * installs a pass-through gate (T006 TP-004: a capture taken before this call must show no trace of
 * this mutation, and this strategy's own retained objects must show no trace of a later decoration
 * applied only to the capture).
 */
public final class GateMutatingValidationStrategy implements RequestValidationStrategy {

    /** The selection id this strategy registers under. */
    public static final String ID = "gate-mutating";

    private final Map<String, OperationSchemas> received = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        schemas.bodySchema().ifPresent(GateMutatingValidationStrategy::mutate);
        for (ParamDescriptor param : op.parameters()) {
            schemas.parameterSchema(param.location(), param.name()).ifPresent(GateMutatingValidationStrategy::mutate);
        }
        received.put(op.operationId(), schemas);
        return Optional.of(RoutingContext::next);
    }

    /**
     * Returns the (mutated) schemas this strategy received for the given operation id.
     *
     * @param operationId the operation id
     * @return the received schemas, or {@code null} if {@code operationId} was never gated
     */
    public OperationSchemas received(String operationId) {
        return received.get(operationId);
    }

    private static void mutate(JsonObject json) {
        for (String key : new ArrayList<>(json.fieldNames())) {
            Object value = json.getValue(key);
            if (value instanceof JsonObject nested) {
                mutate(nested);
            } else if (value instanceof JsonArray array) {
                mutate(array);
            }
        }
        json.put("x-gate", true);
    }

    private static void mutate(JsonArray array) {
        for (Object value : array) {
            if (value instanceof JsonObject nested) {
                mutate(nested);
            } else if (value instanceof JsonArray nestedArray) {
                mutate(nestedArray);
            }
        }
    }
}
