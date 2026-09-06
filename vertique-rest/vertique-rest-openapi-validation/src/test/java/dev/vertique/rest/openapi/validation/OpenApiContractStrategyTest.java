// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategySelector;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.json.schema.OutputUnit;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.validation.SchemaValidationException;
import io.vertx.openapi.validation.ValidatorErrorType;
import io.vertx.openapi.validation.ValidatorException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit-level guards for {@link OpenApiContractValidationStrategy}: its selection id is
 * {@code "openapi-contract"}, it always produces a gate handler (the contract — not the synthesized
 * {@link OperationSchemas} — drives validation), and it is selectable through the shared Set-based
 * {@link RequestValidationStrategySelector} when contributed to the strategy multibinding. It also
 * verifies that validator failures are converted into value-free framework errors, including fallback
 * paths not emitted by the current Vert.x validator implementation but accepted by the mapper contract.
 *
 * <p>The end-to-end body/parameter validation behavior (a missing required field yields a 400, a
 * conforming request reaches dispatch, strict content-type rejection) is exercised against a real
 * Vert.x HTTP server in {@link OpenApiContractStrategyIT}, where the gate runs after {@code BodyHandler}
 * exactly as it does in production.
 */
@ExtendWith(VertxExtension.class)
class OpenApiContractStrategyTest {

    private static final String CONTRACT_PATH = "openapi-contract-strategy-test.json";

    /** A second contract fixture (disjoint operationId, disjoint schema) used to prove per-mount binding. */
    private static final String MOUNT_B_CONTRACT_PATH = "openapi-contract-mount-b.json";

    private OpenApiContractValidationStrategy strategy(Vertx vertx) {
        return new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_PATH).build());
    }

    /**
     * Builds a minimal {@link MountMeta} for use in {@code bindToMount} tests. Only
     * {@link MountMeta#openapiPath()} is inspected by the strategy; the other fields carry
     * representative values.
     *
     * @param openapiPath the OpenAPI contract path to embed in the metadata
     * @return a {@link MountMeta} carrying {@code openapiPath}
     */
    private static MountMeta mountMeta(String openapiPath) {
        return new MountMeta("jaxrs:/api/*", "/api/*", openapiPath, Set.of());
    }

    /**
     * Builds a {@link MountMeta} with an explicit mount id and mount path, distinct from
     * {@link #mountMeta(String)}'s fixed identity. Used where two mounts must be individually
     * addressable (e.g. per-mount cache proofs), since the mount path matters to the sensitivity of
     * those proofs even though the strategy itself keys its contract cache by {@code openapiPath}.
     *
     * @param mountId     the mount identifier to embed in the metadata
     * @param mountPath   the mount path prefix to embed in the metadata
     * @param openapiPath the OpenAPI contract path to embed in the metadata
     * @return a {@link MountMeta} carrying all three values
     */
    private static MountMeta mountMeta(String mountId, String mountPath, String openapiPath) {
        return new MountMeta(mountId, mountPath, openapiPath, Set.of());
    }

    @Test
    @DisplayName("id() is 'openapi-contract'")
    void openApiContractStrategyIdIsOpenApiContract(Vertx vertx) {
        assertEquals("openapi-contract", strategy(vertx).id());
    }

    @Test
    @DisplayName("gateFor() always produces a gate handler (the contract drives validation, not OperationSchemas)")
    void openApiContractStrategyAlwaysInstallsGate(Vertx vertx) {
        JaxRsOperationDescriptor op = op("POST", "/widgets", "createWidget");
        java.util.Optional<Handler<RoutingContext>> gate = strategy(vertx).gateFor(op, OperationSchemas.empty());
        assertTrue(gate.isPresent(), "openapi-contract strategy installs a gate for every operation");
        assertNotNull(gate.orElseThrow());
    }

    @Test
    @DisplayName("Set-based selector returns the openapi-contract strategy when it is in the strategy set")
    void openapiContractStrategySelectedWhenModulePresent(Vertx vertx) {
        OpenApiContractValidationStrategy openapi = strategy(vertx);
        Set<RequestValidationStrategy> set = Set.of(openapi);

        RequestValidationStrategy selected = RequestValidationStrategySelector.select("openapi-contract", set);

        assertEquals("openapi-contract", selected.id());
        assertTrue(selected instanceof OpenApiContractValidationStrategy);
    }

    @Test
    @DisplayName("bindToMount() tolerates a single mount whose path matches the loaded contract path")
    void bindToMountSingleMountMatchingPathIsAllowed(Vertx vertx) {
        OpenApiContractValidationStrategy s = strategy(vertx);
        // The strategy loads CONTRACT_PATH; binding the same path (the default single-mount case) is fine.
        assertDoesNotThrow(() -> s.bindToMount(mountMeta(CONTRACT_PATH)));
        // Re-binding the same path (idempotent / multiple mounts sharing one contract) must also be fine.
        assertDoesNotThrow(() -> s.bindToMount(mountMeta(CONTRACT_PATH)));
    }

    @Test
    @DisplayName("The legacy mount-agnostic gateFor() fails closed once a divergent mount is bound")
    void legacyGateForFailsClosedAfterDivergentBind(Vertx vertx) {
        // The framework calls the 3-arg gateFor, which resolves each mount's own contract. The retained
        // 2-arg form is mount-agnostic and validates against the global contract only, so once a mount
        // declaring a DIFFERENT openapiPath is bound it cannot tell which contract an operation belongs
        // to: it must fail closed naming the bound paths rather than silently validating against the
        // global contract (the divergence guard moved here from bindToMount).
        OpenApiContractValidationStrategy s = strategy(vertx);
        s.bindToMount(mountMeta("jaxrs:/a/*", "/a/*", CONTRACT_PATH));
        s.bindToMount(mountMeta("jaxrs:/b/*", "/b/*", MOUNT_B_CONTRACT_PATH));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> s.gateFor(op("POST", "/widgets", "createWidget"), OperationSchemas.empty()));
        assertTrue(
                ex.getMessage().contains(CONTRACT_PATH) && ex.getMessage().contains(MOUNT_B_CONTRACT_PATH),
                "the message must name both bound contract paths; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("bindToMount() loads one contract per distinct mount openapiPath; each mount's 3-arg gate validates"
            + " against its own contract")
    void bindToMountDivergentPathsLoadsOneContractPerPath(Vertx vertx, VertxTestContext ctx) {
        // The strategy's construction pre-warms CONTRACT_PATH (fixture A: POST /widgets -> createWidget,
        // required: name). mountB's openapiPath is the disjoint fixture B (POST /gadgets -> createGadget,
        // required: sku).
        OpenApiContractValidationStrategy s = strategy(vertx);
        MountMeta mountA = mountMeta("jaxrs:/a/*", "/a/*", CONTRACT_PATH);
        MountMeta mountB = mountMeta("jaxrs:/b/*", "/b/*", MOUNT_B_CONTRACT_PATH);

        assertDoesNotThrow(
                () -> {
                    s.bindToMount(mountA);
                    s.bindToMount(mountB);
                    s.bindToMount(mountA);
                    s.bindToMount(mountB);
                },
                "binding two mounts with distinct openapiPaths, each bound twice, must not throw");

        JaxRsOperationDescriptor createWidget = op("POST", "/widgets", "createWidget");
        JaxRsOperationDescriptor createGadget = op("POST", "/gadgets", "createGadget");
        Handler<RoutingContext> gateA =
                s.gateFor(createWidget, OperationSchemas.empty(), mountA).orElseThrow();
        Handler<RoutingContext> gateB =
                s.gateFor(createGadget, OperationSchemas.empty(), mountB).orElseThrow();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/a")
                .handler(gateA)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        router.post("/b")
                .handler(gateB)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        // Minimal failure handler mirroring the framework REST error pipeline: RestValidationException
        // -> 400, anything else -> 500 (same shape as OpenApiContractStrategyIT's harness).
        router.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            int status = failure instanceof RestValidationException ? 400 : 500;
            rc.response().setStatusCode(status).end(failure == null ? "" : String.valueOf(failure.getMessage()));
        });

        // Valid only under B's contract (requires "sku"); A requires "name" and rejects any undeclared
        // property under additionalProperties:false, so A's gate must reject this body while B's gate
        // must accept it.
        String bodyValidOnlyUnderB = new JsonObject().put("sku", "s").encode();

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(server -> {
            WebClient client = WebClient.create(vertx);
            runGate(client, server.actualPort(), "/a", bodyValidOnlyUnderB)
                    .compose(aStatus -> runGate(client, server.actualPort(), "/b", bodyValidOnlyUnderB)
                            .map(bStatus -> new int[] {aStatus, bStatus}))
                    .onComplete(ctx.succeeding(statuses -> ctx.verify(() -> {
                        try {
                            assertEquals(400, statuses[0], "A's gate must reject a body valid only under B's contract");
                            assertTrue(
                                    statuses[1] >= 200 && statuses[1] < 300,
                                    "B's gate must accept a body valid under its own contract; was " + statuses[1]);
                        } finally {
                            client.close();
                            server.close();
                        }
                        ctx.completeNow();
                    })));
        }));
    }

    @Test
    @DisplayName("bindToMount() fails closed naming the mount id when a mount declares no openapiPath")
    void bindToMountNullOpenapiPathFailsClosed(Vertx vertx) {
        OpenApiContractValidationStrategy s = strategy(vertx);
        MountMeta mountWithNullPath = mountMeta("jaxrs:/null-path/*", "/null-path/*", null);

        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, () -> s.bindToMount(mountWithNullPath));
        assertTrue(
                ex.getMessage().contains("jaxrs:/null-path/*"),
                "the message must name the mount id; was: " + ex.getMessage());

        // An explicitly null GLOBAL openapiPath must not throw and must load nothing (no NPE on the
        // construction pre-warm's cache key).
        assertDoesNotThrow(
                () -> new OpenApiContractValidationStrategy(
                        vertx, JaxRsConfig.builder().openapiPath(null).build()),
                "constructing with an explicitly null global openapiPath must not throw");

        // Once a divergent mount has been bound, the legacy 2-arg gateFor must fail closed naming both
        // bound paths (the relocated divergence guard), while the 3-arg form still returns a gate for a
        // properly-bound mount (per-mount validation is unaffected by the legacy guard).
        OpenApiContractValidationStrategy divergent = strategy(vertx);
        MountMeta mountA = mountMeta("jaxrs:/a/*", "/a/*", CONTRACT_PATH);
        MountMeta mountB = mountMeta("jaxrs:/b/*", "/b/*", MOUNT_B_CONTRACT_PATH);
        divergent.bindToMount(mountA);
        divergent.bindToMount(mountB);

        JaxRsOperationDescriptor createWidget = op("POST", "/widgets", "createWidget");
        IllegalStateException legacyEx = assertThrows(
                IllegalStateException.class, () -> divergent.gateFor(createWidget, OperationSchemas.empty()));
        assertTrue(
                legacyEx.getMessage().contains(CONTRACT_PATH)
                        && legacyEx.getMessage().contains(MOUNT_B_CONTRACT_PATH),
                "the legacy 2-arg gateFor message must name both bound paths; was: " + legacyEx.getMessage());

        assertDoesNotThrow(
                () -> divergent
                        .gateFor(createWidget, OperationSchemas.empty(), mountA)
                        .orElseThrow(),
                "the 3-arg gateFor must still return a gate for a properly-bound mount even after a divergent bind");
    }

    @Nested
    @DisplayName("Validation error mapping")
    class ValidationErrorMapping {

        @Test
        @DisplayName("Plain validator failures retain the cause but expose no raw message")
        void plainValidatorFailureIsSanitized() {
            ValidatorException raw = new ValidatorException("submitted secret", ValidatorErrorType.INVALID_VALUE);

            RestValidationException mapped = OpenApiContractValidationStrategy.mapToRestValidationException(raw);

            assertEquals("Request validation failed", mapped.getMessage());
            assertEquals(List.of(), mapped.errors());
            assertSame(raw, mapped.getCause());
        }

        @Test
        @DisplayName("A schema output without child errors is mapped from the output itself")
        void schemaOutputWithoutChildErrorsProducesSanitizedDetail() {
            OutputUnit output = new OutputUnit()
                    .setKeywordLocation("#/maxLength")
                    .setInstanceLocation("#/pin")
                    .setError("String contains submitted secret");
            SchemaValidationException raw =
                    new SchemaValidationException("submitted secret", ValidatorErrorType.INVALID_VALUE, output, null);

            RestValidationException mapped = OpenApiContractValidationStrategy.mapToRestValidationException(raw);

            assertEquals("Request validation failed", mapped.getMessage());
            assertEquals(
                    List.of(new ValidationErrorDetail("#/pin", "must be shorter", "body", "maxLength", null)),
                    mapped.errors());
            assertSame(raw, mapped.getCause());
        }

        @Test
        @DisplayName("Structural-only schema output produces a generic value-free detail")
        void structuralOnlySchemaOutputProducesGenericDetail() {
            OutputUnit structural = new OutputUnit()
                    .setKeywordLocation("#/properties")
                    .setInstanceLocation("#/pin")
                    .setError("submitted secret");
            OutputUnit output = new OutputUnit().setErrors(List.of(structural));
            SchemaValidationException raw =
                    new SchemaValidationException("submitted secret", ValidatorErrorType.INVALID_VALUE, output, null);

            RestValidationException mapped = OpenApiContractValidationStrategy.mapToRestValidationException(raw);

            assertEquals(List.of(new ValidationErrorDetail("", "is invalid", "body", null, null)), mapped.errors());
        }

        @Test
        @DisplayName("A schema failure without output remains a sanitized validation failure")
        void schemaFailureWithoutOutputProducesNoDetails() {
            SchemaValidationException raw =
                    new SchemaValidationException("submitted secret", ValidatorErrorType.INVALID_VALUE, null, null);

            RestValidationException mapped = OpenApiContractValidationStrategy.mapToRestValidationException(raw);

            assertEquals("Request validation failed", mapped.getMessage());
            assertEquals(List.of(), mapped.errors());
            assertSame(raw, mapped.getCause());
        }
    }

    /** Builds a minimal descriptor: only the identity fields are read by the gate. */
    private static JaxRsOperationDescriptor op(String method, String route, String operationId) {
        return StubDescriptors.builder()
                .operationId(operationId)
                .httpMethod(method)
                .routeTemplate(route)
                .build();
    }

    /**
     * Posts {@code jsonBody} to {@code path} on the given already-listening server and resolves the
     * response status code. A gate installed ahead of a route must have already reached {@code ctx.next()}
     * or {@code ctx.fail(...)} for the response to complete, so this drives the gate exactly as production
     * does — over a real request — without building a full mount/registrar.
     *
     * @param client   the client to send through
     * @param port     the server's bound port
     * @param path     the route the gate is installed on
     * @param jsonBody the request body
     * @return the resolved HTTP status code
     */
    private static Future<Integer> runGate(WebClient client, int port, String path, String jsonBody) {
        return client.post(port, "127.0.0.1", path)
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer(jsonBody))
                .map(resp -> resp.statusCode());
    }
}
