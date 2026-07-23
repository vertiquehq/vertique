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
 * Verifies that {@link ServiceContractProcessor} rejects handler classes with overloaded public
 * method names, and that private helpers sharing a name with a public operation are not
 * falsely rejected.
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#validateHandlerMethods}
 * ({@code MethodValidator.java:80,106,191}).
 *
 * <p>Any duplicate-name <em>public</em> method on a handler class triggers a full-stop rejection.
 * No "best overload" heuristic is attempted — the handler method is resolved by name only.
 */
class ServiceContractProcessorHandlerOverloadTest {

    @Test
    @DisplayName("handler class with two public methods named getUser → FAILED")
    void handlerClassWithOverloadedMethodName_fails() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("get-user")
                    Future<String> getUser(String userId);
                }
                """);

        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import dev.vertique.security.SecurityContext;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                    // Overload — same name, different signature → rejected
                    public Future<String> getUser(String userId, SecurityContext sc) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("getUser");
    }

    @Test
    @DisplayName("handler with public ship() and private ship() helper → succeeds (private is not a public overload)")
    void handlerWithPrivateHelperOfSameName_succeeds() {
        JavaFileObject contract = SourceFiles.inline("com.example.ShipService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "ship-service", namespace = "logistics")
                public interface ShipService {
                    @ServiceOperation("ship")
                    Future<String> ship(String orderId);
                }
                """);

        JavaFileObject handler = SourceFiles.inline("com.example.ShipServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class ShipServiceHandler implements ServiceHandler<ShipService> {
                    @Inject ShipServiceHandler() {}
                    public Future<String> ship(String orderId) {
                        return Future.succeededFuture(orderId);
                    }
                    // Private helper with the same name — must NOT trigger overload rejection
                    private void ship() {}
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources).assertSuccess();
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
