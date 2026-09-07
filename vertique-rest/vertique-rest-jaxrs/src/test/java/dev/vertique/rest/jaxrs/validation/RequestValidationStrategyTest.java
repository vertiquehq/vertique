// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link RequestValidationStrategy} SPI contract and the {@code none} strategy
 * ({@link NoneValidationStrategy}).
 *
 * <p>The web-validation gate behavior lives in {@code WebValidationGateTest} in
 * {@code vertique-rest-validation}, where the vertx-json-schema gate is implemented. The
 * {@code RestValidationException} -> 400 {@code application/problem+json} mapping parity is verified
 * in {@code RestValidationExceptionMappingTest} (same package as the package-private mapper factory).
 */
class RequestValidationStrategyTest {

    // --- Descriptor stub ---

    private static JaxRsOperationDescriptor op(List<ParamDescriptor> params, Optional<BodyDescriptor> body) {
        return StubOperationDescriptor.builder()
                .operationId("op")
                .httpMethod("POST")
                .routeTemplate("/things")
                .parameters(params)
                .body(body)
                .build();
    }

    /** A minimal in-test strategy whose {@code id()} is {@code "web-validation"}. */
    private static final class WebValidationLikeStrategy implements RequestValidationStrategy {
        @Override
        public String id() {
            return "web-validation";
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.of(RoutingContext::next);
        }
    }

    /**
     * A strategy overriding only the 2-arg {@link RequestValidationStrategy#gateFor(JaxRsOperationDescriptor,
     * OperationSchemas)} form, recording each invocation and returning a fixed {@link Optional}. Used to
     * prove that the mount-aware 3-arg default delegates to the 2-arg form rather than reimplementing gate
     * construction.
     */
    private static final class TwoArgOnlyStrategy implements RequestValidationStrategy {

        private final Optional<Handler<RoutingContext>> fixedGate = Optional.of(RoutingContext::next);
        private int gateForCallCount = 0;

        @Override
        public String id() {
            return "two-arg-only";
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            gateForCallCount++;
            return fixedGate;
        }

        Optional<Handler<RoutingContext>> fixedGate() {
            return fixedGate;
        }

        int gateForCallCount() {
            return gateForCallCount;
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("A strategy's id() is a plain string (not an enum) — 'web-validation'")
    void requestValidationStrategyIdMethodIsString() {
        RequestValidationStrategy strategy = new WebValidationLikeStrategy();

        String id = strategy.id();

        assertInstanceOf(String.class, id);
        assertEquals("web-validation", id);
    }

    @Test
    @DisplayName("NoneValidationStrategy.gateFor returns Optional.empty (no gate installed)")
    void noneStrategyGateForReturnsEmpty() {
        NoneValidationStrategy none = new NoneValidationStrategy();

        Optional<Handler<RoutingContext>> gate = none.gateFor(
                op(List.of(), Optional.empty()), OperationSchemas.builder().build());

        assertTrue(gate.isEmpty(), "none strategy must install no gate");
        assertEquals("none", none.id());
    }

    @Test
    @DisplayName("Under 'none', a body violating a constraint is not gate-rejected (gateFor stays empty)")
    void noneStrategyDispatchesBodyViolatingConstraint() {
        NoneValidationStrategy none = new NoneValidationStrategy();
        // A body schema that requires 'name' — under 'none' it is never consulted because no gate runs.
        OperationSchemas schemas = OperationSchemas.builder()
                .bodySchema(new io.vertx.core.json.JsonObject()
                        .put("type", "object")
                        .put("required", new io.vertx.core.json.JsonArray().add("name")))
                .build();

        Optional<Handler<RoutingContext>> gate = none.gateFor(op(List.of(), Optional.empty()), schemas);

        // No gate handler exists, so nothing can reject the violating body — dispatch proceeds.
        assertTrue(gate.isEmpty(), "none strategy never rejects: no gate handler is produced");
    }

    @Test
    @DisplayName("The default 3-arg gateFor(op, schemas, mount) delegates to the 2-arg form")
    void threeArgGateForDelegatesToTwoArgByDefault() {
        TwoArgOnlyStrategy strategy = new TwoArgOnlyStrategy();
        MountMeta mount = new MountMeta("jaxrs:/things", "/things", "openapi.json", Set.of());

        Optional<Handler<RoutingContext>> gate = strategy.gateFor(
                op(List.of(), Optional.empty()), OperationSchemas.builder().build(), mount);

        assertEquals(1, strategy.gateForCallCount(), "the 2-arg form must run exactly once");
        assertSame(
                strategy.fixedGate(),
                gate,
                "the 3-arg default must return the identical Optional produced by the 2-arg form");
    }
}
