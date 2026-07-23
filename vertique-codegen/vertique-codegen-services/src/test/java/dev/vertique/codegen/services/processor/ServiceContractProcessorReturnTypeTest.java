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
 * Verifies that {@link ServiceContractProcessor} rejects contract methods whose return type is
 * not a parameterized {@code Future<T>}.
 *
 * <p>Mirrors {@code dev.vertique.services.ReturnTypeResolver} (runtime check at
 * {@code ReturnTypeResolver.java:26-63}).
 */
class ServiceContractProcessorReturnTypeTest {

    @Test
    @DisplayName("contract method returning String (not Future) → FAILED with Future error")
    void contractMethodReturnsString_failsWithFutureError() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("get-user")
                    String getUser(String userId);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public String getUser(String userId) { return userId; }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("Future");
    }

    @Test
    @DisplayName("contract method returning void (not Future) → FAILED with Future error")
    void contractMethodReturnsVoid_failsWithFutureError() {
        JavaFileObject contract = SourceFiles.inline("com.example.NotifyService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                @ServiceContract(value = "notify-service", namespace = "integration")
                public interface NotifyService {
                    @ServiceOperation("send")
                    void send(String message);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.NotifyServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                public class NotifyServiceImpl implements NotifyService {
                    @Inject NotifyServiceImpl() {}
                    @Override public void send(String message) {}
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("Future");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
