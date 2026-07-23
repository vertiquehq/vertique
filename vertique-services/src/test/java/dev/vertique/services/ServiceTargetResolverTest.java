// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the O(1) resolution logic in {@link DefaultServiceTargetResolver} backed by
 * {@link ServiceContractRegistry}. Covers all three overloads of {@code resolve()} and
 * the error paths for unknown targets, contracts, and operations.
 */
class ServiceTargetResolverTest {

    // --- Contract Fixtures ---

    @ServiceContract(namespace = "integration", value = "test-svc")
    interface TestService {
        @ServiceOperation("op-one")
        Future<String> operationOne(String input);

        @ServiceOperation("op-two")
        Future<Void> operationTwo(String input);
    }

    static class TestServiceImpl implements TestService {
        @Override
        public Future<String> operationOne(String input) {
            return Future.succeededFuture(input);
        }

        @Override
        public Future<Void> operationTwo(String input) {
            return Future.succeededFuture();
        }
    }

    // --- Test Setup ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private ServiceTargetResolver resolver;

    @BeforeEach
    void setUp() {
        ServiceContractRegistry registry = ServiceContractRegistry.build(
                Set.of(new TestServiceImpl()), Set.of(), new JsonObject(), configParser());
        resolver = new DefaultServiceTargetResolver(registry);
    }

    // --- resolve(String targetId) ---

    @Nested
    @DisplayName("resolve(String targetId)")
    class ResolveByTargetId {

        @Test
        @DisplayName("resolves a known target id to the correct ResolvedServiceTarget")
        void resolvesKnownTargetId() {
            ResolvedServiceTarget target = resolver.resolve("integration.test-svc.op-one");

            assertNotNull(target);
            assertEquals("integration.test-svc.op-one", target.targetId());
            assertEquals("integration", target.namespace());
            assertEquals("test-svc", target.name());
            assertEquals("op-one", target.operation());
            assertEquals("services/integration/test-svc/op-one", target.address());
            assertNotNull(target.meta());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for an unknown target id")
        void throwsForUnknownTargetId() {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("unknown.target.id"));
        }
    }

    // --- resolve(Class<?> contract, Method method) ---

    @Nested
    @DisplayName("resolve(Class<?> contract, Method method)")
    class ResolveByContractAndMethod {

        @Test
        @DisplayName("resolves a known contract and method to the correct ResolvedServiceTarget")
        void resolvesKnownContractAndMethod() throws NoSuchMethodException {
            Method method = TestService.class.getMethod("operationOne", String.class);
            ResolvedServiceTarget target = resolver.resolve(TestService.class, method);

            assertNotNull(target);
            assertEquals("integration.test-svc.op-one", target.targetId());
            assertEquals("integration", target.namespace());
            assertEquals("test-svc", target.name());
            assertEquals("op-one", target.operation());
            assertEquals("services/integration/test-svc/op-one", target.address());
            assertNotNull(target.meta());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for an unregistered contract class")
        void throwsForUnknownContract() throws NoSuchMethodException {
            Method method = TestService.class.getMethod("operationOne", String.class);
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(String.class, method));
        }
    }

    // --- resolve(Class<?> contract, String operationId) ---

    @Nested
    @DisplayName("resolve(Class<?> contract, String operationId)")
    class ResolveByContractAndOperationId {

        @Test
        @DisplayName("resolves a known contract and operation id to the correct ResolvedServiceTarget")
        void resolvesKnownContractAndOperationId() {
            ResolvedServiceTarget target = resolver.resolve(TestService.class, "op-one");

            assertNotNull(target);
            assertEquals("integration.test-svc.op-one", target.targetId());
            assertEquals("integration", target.namespace());
            assertEquals("test-svc", target.name());
            assertEquals("op-one", target.operation());
            assertEquals("services/integration/test-svc/op-one", target.address());
            assertNotNull(target.meta());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for an unknown operation id on a known contract")
        void throwsForUnknownOperationId() {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(TestService.class, "no-such-op"));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for an unregistered contract class")
        void throwsForUnknownContract() {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(String.class, "op-one"));
        }
    }
}
