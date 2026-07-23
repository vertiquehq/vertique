// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceContractRegistryTest {

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Contract Fixtures ---

    @ServiceContract(namespace = "integration", value = "test-service")
    interface GreetService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("list")
        Future<List<String>> list();

        @ServiceOperation("ping")
        Future<Void> ping();
    }

    @ServiceContract(namespace = "business", value = "order-service")
    interface OrderService {
        @ServiceOperation("createOrder")
        Future<String> createOrder(String item);
    }

    // --- Implementation Fixtures ---

    static class GreetServiceImpl implements GreetService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<List<String>> list() {
            return Future.succeededFuture(List.of());
        }

        @Override
        public Future<Void> ping() {
            return Future.succeededFuture();
        }
    }

    static class OrderServiceImpl implements OrderService {
        @Override
        public Future<String> createOrder(String item) {
            return Future.succeededFuture("order-123");
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Registry built from a valid service contains the expected entry")
    void shouldBuildRegistryFromValidServices() {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), configParser());
        assertFalse(registry.entries().isEmpty());
        assertEquals(1, registry.entries().size());
    }

    @Test
    @DisplayName("resolve() returns correct entry with base address and operations")
    void shouldResolveContractEntry() {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), configParser());
        ServiceContractRegistry.ContractEntry<GreetService> entry = registry.resolve(GreetService.class);

        assertNotNull(entry);
        assertEquals("services/integration/test-service", entry.baseAddress());
        assertTrue(entry.operations().containsKey("greet"));
        assertTrue(entry.operations().containsKey("list"));
        assertTrue(entry.operations().containsKey("ping"));
        assertEquals(3, entry.operations().size());
    }

    @Test
    @DisplayName("resolve() throws IllegalArgumentException for an unregistered contract")
    void shouldThrowOnUnregisteredContract() {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), configParser());
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(OrderService.class));
    }

    @Test
    @DisplayName("Config key services.contracts.{namespace}.{name}.instances sets instance count")
    void shouldApplyDeploymentOptionsFromConfig() {
        JsonObject config = new JsonObject()
                .put(
                        "services",
                        new JsonObject()
                                .put(
                                        "contracts",
                                        new JsonObject()
                                                .put(
                                                        "integration",
                                                        new JsonObject()
                                                                .put(
                                                                        "test-service",
                                                                        new JsonObject().put("instances", 3)))));
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), config, configParser());
        ServiceContractRegistry.ContractEntry<GreetService> entry = registry.resolve(GreetService.class);
        assertEquals(3, entry.deploymentOptions().getInstances());
    }

    @Test
    @DisplayName("Config key services.contracts.{namespace}.{name}.worker=true sets WORKER threading model")
    void shouldApplyWorkerThreadingModel() {
        JsonObject config = new JsonObject()
                .put(
                        "services",
                        new JsonObject()
                                .put(
                                        "contracts",
                                        new JsonObject()
                                                .put(
                                                        "integration",
                                                        new JsonObject()
                                                                .put(
                                                                        "test-service",
                                                                        new JsonObject().put("worker", true)))));
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), config, configParser());
        ServiceContractRegistry.ContractEntry<GreetService> entry = registry.resolve(GreetService.class);
        assertEquals(ThreadingModel.WORKER, entry.deploymentOptions().getThreadingModel());
    }

    @Test
    @DisplayName("No config produces 1 instance and default threading model")
    void shouldUseDefaultDeploymentOptions() {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl()), configParser());
        ServiceContractRegistry.ContractEntry<GreetService> entry = registry.resolve(GreetService.class);
        assertEquals(1, entry.deploymentOptions().getInstances());
        assertNotEquals(ThreadingModel.WORKER, entry.deploymentOptions().getThreadingModel());
    }

    @Test
    @DisplayName("entries() returns all registered contract entries")
    void shouldReturnAllEntries() {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new GreetServiceImpl(), new OrderServiceImpl()), configParser());
        assertEquals(2, registry.entries().size());
        boolean hasGreet =
                registry.entries().stream().anyMatch(e -> e.baseAddress().equals("services/integration/test-service"));
        boolean hasOrder =
                registry.entries().stream().anyMatch(e -> e.baseAddress().equals("services/business/order-service"));
        assertTrue(hasGreet, "Expected services/integration/test-service entry");
        assertTrue(hasOrder, "Expected services/business/order-service entry");
    }
}
