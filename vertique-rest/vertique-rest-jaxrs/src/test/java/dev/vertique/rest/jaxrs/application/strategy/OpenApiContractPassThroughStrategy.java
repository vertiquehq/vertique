// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.strategy;

import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-source pass-through strategy carrying the id {@code "openapi-contract"}: reports
 * {@link #resolvesOperationsFromMountContract()} {@code true}, so the mount validator's flag-gated
 * location parse runs (TP-009, TP-010), and records every {@link #bindToMount(MountMeta)} location
 * plus, for each gate built through the mount-aware {@code gateFor}, the pair (operationId,
 * {@code mountMeta.openapiPath()}) both at router-build time and once more per request, before
 * calling {@code ctx.next()} (TP-007). Installs a gate for every operation so the per-request
 * recording runs; never fails a request.
 */
public final class OpenApiContractPassThroughStrategy implements RequestValidationStrategy {

    private final List<String> bindToMountLocations = new CopyOnWriteArrayList<>();
    private final Map<String, String> buildTimeLocationsByOperationId = new ConcurrentHashMap<>();
    private final List<Map.Entry<String, String>> requestTimeLocations = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
        return "openapi-contract";
    }

    @Override
    public boolean resolvesOperationsFromMountContract() {
        return true;
    }

    @Override
    public void bindToMount(MountMeta mountMeta) {
        bindToMountLocations.add(mountMeta.openapiPath());
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        return gateFor(op, schemas, null);
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(
            JaxRsOperationDescriptor op, OperationSchemas schemas, @Nullable MountMeta mount) {
        String location = mount != null ? mount.openapiPath() : null;
        buildTimeLocationsByOperationId.put(op.operationId(), location);
        return Optional.of(ctx -> {
            requestTimeLocations.add(Map.entry(op.operationId(), location));
            ctx.next();
        });
    }

    /**
     * Returns every location this strategy's {@link #bindToMount(MountMeta)} saw, in call order.
     *
     * @return the recorded locations
     */
    public List<String> bindToMountLocations() {
        return List.copyOf(bindToMountLocations);
    }

    /**
     * Returns the router-build-time (operationId, location) pairs, one per gated operation.
     *
     * @return the recorded pairs, by operationId
     */
    public Map<String, String> buildTimeLocationsByOperationId() {
        return Map.copyOf(buildTimeLocationsByOperationId);
    }

    /**
     * Returns the per-request (operationId, location) pairs recorded by each gate invocation, in
     * request order.
     *
     * @return the recorded per-request pairs
     */
    public List<Map.Entry<String, String>> requestTimeLocations() {
        return List.copyOf(requestTimeLocations);
    }
}
