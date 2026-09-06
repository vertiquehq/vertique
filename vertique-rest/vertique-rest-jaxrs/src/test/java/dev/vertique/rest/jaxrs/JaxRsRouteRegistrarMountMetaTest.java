// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link JaxRsRouteRegistrar#registerAll}, invoked from {@link JaxRsRouterMount#createRouter},
 * threads each mount's own {@link MountMeta} to a strategy overriding the mount-aware 3-arg
 * {@link RequestValidationStrategy#gateFor(JaxRsOperationDescriptor, OperationSchemas, MountMeta)} — and
 * never falls back to the 2-arg form for such a strategy.
 */
class JaxRsRouteRegistrarMountMetaTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    @DisplayName("Each mount's operations are gated with that mount's own MountMeta.openapiPath()")
    void registrarPassesEachMountsMetaToGateFor() throws Exception {
        RecordingMountAwareStrategy strategy = new RecordingMountAwareStrategy();
        JaxRsRouterMount.Factory factory = buildFactory(strategy);

        createRouter(factory, "/a/*", "a.json");
        createRouter(factory, "/b/*", "b.json");

        assertEquals(
                List.of("a.json", "b.json"),
                strategy.recordedOpenapiPaths(),
                "mount A's operation must record a.json and mount B's operation must record b.json");
        assertFalse(strategy.twoArgGateForCalled(), "the 2-arg gateFor form must never be called");
    }

    private void createRouter(JaxRsRouterMount.Factory factory, String mountPath, String openapiPath) throws Exception {
        factory.create(mountPath, openapiPath, Set.of(new PingResource()))
                .createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static JaxRsRouterMount.Factory buildFactory(RequestValidationStrategy strategy) {
        DefaultExceptionMapper defaultMapper = RestModule.defaultExceptionMapper();
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        List<ResponseBodyEncoder> encoders = List.of(new StringBodyEncoder(), new JsonBodyEncoder());
        DefaultResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig =
                JaxRsConfig.builder().validationStrategy(strategy.id()).build();

        return new JaxRsRouterMount.Factory(
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                restExceptionMapper,
                registry,
                Set.of(),
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null,
                Optional.empty(),
                List.of(),
                encoders,
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Set.of(strategy),
                Optional.empty());
    }

    /** Minimal resource making each mount traverse strategy selection and route registration. */
    @Path("/ping")
    public static class PingResource {

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mountMetaPing")
        public String ping() {
            return "pong";
        }
    }

    /**
     * Strategy overriding only the mount-aware 3-arg {@code gateFor}, recording {@code
     * mount.openapiPath()} for each operation it is asked to gate; the 2-arg form is implemented only to
     * satisfy the abstract member and records whether it was ever called (it must not be, since this
     * strategy overrides the 3-arg form).
     */
    private static final class RecordingMountAwareStrategy implements RequestValidationStrategy {

        private final List<String> recordedOpenapiPaths = new CopyOnWriteArrayList<>();
        private volatile boolean twoArgGateForCalled = false;

        @Override
        public String id() {
            return "recording-mount-aware";
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            twoArgGateForCalled = true;
            return Optional.empty();
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(
                JaxRsOperationDescriptor op, OperationSchemas schemas, MountMeta mount) {
            recordedOpenapiPaths.add(mount.openapiPath());
            return Optional.empty();
        }

        List<String> recordedOpenapiPaths() {
            return List.copyOf(recordedOpenapiPaths);
        }

        boolean twoArgGateForCalled() {
            return twoArgGateForCalled;
        }
    }
}
