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
 * Verifies that {@link ServiceContractProcessor} rejects contract interfaces with overloaded
 * method names.
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#detectOverloads}
 * ({@code MethodValidator.java:27-41}).
 *
 * <p>Service contracts require unique method names per operation because operation names are
 * derived from method names when {@code @ServiceOperation} is absent. Two methods with the same
 * name would produce an event bus address collision.
 */
class ServiceContractProcessorContractOverloadTest {

    @Test
    @DisplayName("contract interface with two methods named getUser → FAILED with overload error")
    void contractInterfaceWithOverloadedMethodName_failsWithOverloadError() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("get-user-by-id")
                    Future<String> getUser(String userId);
                    @ServiceOperation("get-user-by-name")
                    Future<String> getUser(String firstName, String lastName);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                    @Override public Future<String> getUser(String firstName, String lastName) {
                        return Future.succeededFuture(firstName);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("getUser");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
