// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ServiceContractProcessor} rejects contracts where two methods resolve to
 * the same operation name.
 *
 * <p>Mirrors duplicate-operation detection in {@code dev.vertique.services.ServiceRegistrar}
 * ({@code ServiceRegistrar.java:159-170}).
 *
 * <p>Specifically tests the scenario where one method carries {@code @ServiceOperation("foo")} and
 * a second method is named {@code foo()} with no annotation — both resolve to the operation name
 * {@code "foo"}, which would cause an event bus address collision.
 */
class ServiceContractProcessorOperationCollisionTest {

    @Test
    @DisplayName("@ServiceOperation(\"foo\") and method named foo() → FAILED with collision error")
    void explicitOpAnnotationCollidesWithMethodName_failsWithCollisionError() {
        JavaFileObject contract = SourceFiles.inline("com.example.OrderService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "order-service", namespace = "integration")
                public interface OrderService {
                    @ServiceOperation("foo")
                    Future<String> submitOrder(String id);
                    // This method name "foo" + no annotation → resolves to operation "foo" → collision
                    Future<String> foo(String id);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.OrderServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class OrderServiceImpl implements OrderService {
                    @Inject OrderServiceImpl() {}
                    @Override public Future<String> submitOrder(String id) {
                        return Future.succeededFuture(id);
                    }
                    @Override public Future<String> foo(String id) {
                        return Future.succeededFuture(id);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("foo");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
