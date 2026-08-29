// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration-style unit tests for {@link ServiceVerticle}.
 *
 * <p>Verifies that {@link ServiceVerticle#start(io.vertx.core.Promise)} registers event bus
 * consumers for all operations in the contract entry, and that registered consumers correctly
 * handle inbound requests by dispatching to the service implementation.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceVerticleTest {

    // --- Contract fixture ---

    @ServiceContract(namespace = "svc", value = "test-svc")
    interface SimpleService {
        @ServiceOperation("hello")
        Future<String> hello(String name);
    }

    static class SimpleServiceImpl implements SimpleService {
        @Override
        public Future<String> hello(String name) {
            return Future.succeededFuture("hi " + name);
        }
    }

    // --- Setup ---

    private static ServiceContractRegistry registry;

    /**
     * Registers codecs and builds the contract registry before any test runs.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // Already registered — safe to continue
        }
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered — safe to continue
        }

        registry = ServiceContractRegistry.build(
                Set.of(new SimpleServiceImpl()),
                new JsonObject(),
                new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ctx.completeNow();
    }

    // --- Tests ---

    @Test
    @DisplayName("start() should register consumers for all operations in the contract entry")
    void start_registersConsumersForAllOperations(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ServiceContractRegistry.ContractEntry<SimpleService> entry = registry.resolve(SimpleService.class);

        ServiceVerticle<SimpleService> verticle =
                new ServiceVerticle<>(entry, new ServiceExceptionMapper(), List.of(), null, null);

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(deploymentId -> {
            ctx.verify(() -> assertTrue(!deploymentId.isEmpty(), "deployment ID should be non-empty"));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    @Test
    @DisplayName("Deployed verticle should respond to requests on the registered event bus address")
    void deployedVerticle_respondsToRequests(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ServiceContractRegistry.ContractEntry<SimpleService> entry = registry.resolve(SimpleService.class);

        ServiceVerticle<SimpleService> verticle =
                new ServiceVerticle<>(entry, new ServiceExceptionMapper(), List.of(), null, null);

        String address = entry.operations().get("hello").address();
        DeliveryOptions bodyOptions = new DeliveryOptions().setCodecName("dispatch.envelope");
        DispatchEnvelope<String> body =
                DispatchEnvelope.of("world", dev.vertique.core.eventbus.DispatchMetadata.empty());

        vertx.deployVerticle(verticle)
                .compose(id -> vertx.eventBus().<Object>request(address, body, bodyOptions))
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        Result<?> result = (Result<?>) reply.body();
                        assertTrue(result.isSuccess(), "result should be success");
                        assertEquals("hi world", result.get(), "should echo with 'hi ' prefix");
                    });
                    ctx.completeNow();
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    @Test
    @DisplayName("Verticle without policy annotations should start without errors")
    void verticle_withNoPolicies_startsSuccessfully(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ServiceContractRegistry.ContractEntry<SimpleService> entry = registry.resolve(SimpleService.class);

        ServiceVerticle<SimpleService> verticle =
                new ServiceVerticle<>(entry, new ServiceExceptionMapper(), List.of(), null, null);

        vertx.deployVerticle(verticle).onComplete(ctx.succeeding(id -> ctx.completeNow()));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }
}
