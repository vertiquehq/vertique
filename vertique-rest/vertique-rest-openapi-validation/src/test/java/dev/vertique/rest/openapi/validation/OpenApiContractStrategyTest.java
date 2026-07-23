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
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategySelector;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.OutputUnit;
import io.vertx.junit5.VertxExtension;
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

    @Test
    @DisplayName("id() is 'openapi-contract'")
    void openApiContractStrategyIdIsOpenApiContract(Vertx vertx) {
        assertEquals("openapi-contract", strategy(vertx).id());
    }

    @Test
    @DisplayName("gateFor() always produces a gate handler (the contract drives validation, not OperationSchemas)")
    void openApiContractStrategyAlwaysInstallsGate(Vertx vertx) {
        JaxRsOperationDescriptor op = TestDescriptors.op("POST", "/widgets", "createWidget");
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
    @DisplayName("bindToMount() fails closed when two mounts declare divergent openapiPaths")
    void bindToMountDivergentMountPathsFailClosed(Vertx vertx) {
        OpenApiContractValidationStrategy s = strategy(vertx);
        s.bindToMount(mountMeta(CONTRACT_PATH));
        // A second mount declaring a DIFFERENT contract path must fail fast: the singleton strategy
        // resolves one global contract and cannot validate this mount's operations against a different
        // contract. It must throw rather than silently validate against the first mount's contract.
        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, () -> s.bindToMount(mountMeta("other-openapi.json")));
        assertTrue(
                ex.getMessage().contains("other-openapi.json")
                        && ex.getMessage().contains(CONTRACT_PATH),
                "the message must name both divergent contract paths; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("bindToMount() fails closed when a single mount's path diverges from the loaded contract path")
    void bindToMountSingleDivergentMountFailsClosed(Vertx vertx) {
        // The strategy loads CONTRACT_PATH (the global jaxrs.openapiPath). If the ONLY mount declares a
        // different openapiPath, the strategy would validate that mount's operations against the global
        // contract, not the mount's — a silent mismatch even with one mount. It must fail closed.
        OpenApiContractValidationStrategy s = strategy(vertx);
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class, () -> s.bindToMount(mountMeta("divergent-openapi.json")));
        assertTrue(
                ex.getMessage().contains("divergent-openapi.json"),
                "the message must name the divergent mount path; was: " + ex.getMessage());
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

    /** Minimal {@link JaxRsOperationDescriptor} test fixtures: only identity fields are read by the gate. */
    static final class TestDescriptors {
        private TestDescriptors() {}

        static JaxRsOperationDescriptor op(String method, String route, String operationId) {
            return new JaxRsOperationDescriptor() {
                @Override
                public String operationId() {
                    return operationId;
                }

                @Override
                public String httpMethod() {
                    return method;
                }

                @Override
                public String routeTemplate() {
                    return route;
                }

                @Override
                public java.util.List<String> consumes() {
                    return java.util.List.of();
                }

                @Override
                public java.util.List<String> produces() {
                    return java.util.List.of();
                }

                @Override
                public dev.vertique.rest.core.security.SecurityPolicy securityPolicy() {
                    return new dev.vertique.rest.core.security.SecurityPolicy.None();
                }

                @Override
                public java.util.List<dev.vertique.rest.core.routing.SecurityRequirementSet> securityRequirementSets() {
                    return java.util.List.of();
                }

                @Override
                public java.util.List<java.lang.annotation.Annotation> methodAnnotations() {
                    return java.util.List.of();
                }

                @Override
                public java.util.List<java.lang.annotation.Annotation> classAnnotations() {
                    return java.util.List.of();
                }

                @Override
                public <A extends java.lang.annotation.Annotation> java.util.Optional<A> findAnnotation(Class<A> type) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.List<ParamDescriptor> parameters() {
                    return java.util.List.of();
                }

                @Override
                public java.util.List<FilePartDescriptor> fileParts() {
                    return java.util.List.of();
                }

                @Override
                public java.util.Optional<BodyDescriptor> body() {
                    return java.util.Optional.empty();
                }
            };
        }
    }
}
