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
 * Verifies the happy-path handler pattern for {@link ServiceContractProcessor}.
 *
 * <p>The handler pattern uses a {@code ServiceHandler<C>} implementation where the handler
 * method may declare additional {@code DISPATCH_CONTEXT} parameters (e.g., {@code SecurityContext})
 * beyond the contract method's payload parameters.
 *
 * <p>Asserts that:
 * <ul>
 *   <li>{@code .handlerMethod(...)} is generated with the handler class and method.</li>
 *   <li>{@code .handlerParam("userId", PAYLOAD, ...)} and
 *       {@code .handlerParam("sc", DISPATCH_CONTEXT, ...)} appear in the generated contributor.</li>
 *   <li>The contract method reference uses {@code UserService.class.getMethod(...)}-style
 *       {@code resolveMethod(...)} calls.</li>
 * </ul>
 */
class ServiceContractProcessorHandlerPositiveTest {

    @Test
    @DisplayName("handler-pattern happy path generates contributor with handlerMethod and handlerParams")
    void handlerPatternHappyPath_generatesContributorWithHandlerMethodAndParams() {
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
                    public Future<String> getUser(String userId, SecurityContext sc) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, handler);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // --- Assert contributor is generated ---
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "handlerMethod(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "handlerParam(");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "PAYLOAD");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "DISPATCH_CONTEXT");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceHandler");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserService.class");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "resolveMethod(");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
