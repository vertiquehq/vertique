// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies structural constraints on the type argument of {@code ServiceHandler<C>}.
 *
 * <p>Mirrors {@code dev.vertique.services.ContractDiscovery#findContract}
 * ({@code ContractDiscovery.java:35-76}).
 *
 * <p>Cases covered:
 * <ul>
 *   <li>{@code ServiceHandler<NotAContract>} where {@code NotAContract} lacks
 *       {@code @ServiceContract} → the type arg is not a contract, so the handler is silently
 *       ignored (not a service impl). No contributor generated.</li>
 *   <li>Handler implements {@code ServiceHandler<UserService>} AND {@code UserService} directly
 *       (double-pattern) → FAILED.</li>
 *   <li>Handler implements {@code ServiceHandler<UserService>} AND an additional
 *       {@code @ServiceContract} interface → FAILED.</li>
 * </ul>
 */
class ServiceContractProcessorHandlerContractTypeTest {

    private static final JavaFileObject USER_SERVICE_CONTRACT = SourceFiles.inline("com.example.UserService", """
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

    @Test
    @DisplayName("ServiceHandler<NotAContract> (no @ServiceContract) → silently ignored, no contributor")
    void handlerWithNonContractTypeArg_silentlyIgnored() {
        JavaFileObject notAContract = SourceFiles.inline("com.example.NotAContract", """
                package com.example;
                // No @ServiceContract annotation
                public interface NotAContract {}
                """);

        JavaFileObject handler = SourceFiles.inline("com.example.NotAContractHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                public class NotAContractHandler implements ServiceHandler<NotAContract> {
                    @Inject NotAContractHandler() {}
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, notAContract, handler);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        // Handler's type arg is not annotated with @ServiceContract → silently ignored
        result.assertSuccess();
        assertTrue(
                result.compilation()
                        .generatedSourceFile("com.example.NotAContract_ContractContributor")
                        .isEmpty(),
                "Expected no contributor when type arg lacks @ServiceContract");
    }

    @Test
    @DisplayName("implements ServiceHandler<UserService> AND UserService directly (double-pattern) → FAILED")
    void doublePattern_handlerAndDirect_fails() {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                // Double-pattern: both handler and direct impl
                public class UserServiceHandler implements ServiceHandler<UserService>, UserService {
                    @Inject UserServiceHandler() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources).assertFailed();
    }

    @Test
    @DisplayName("implements ServiceHandler<UserService> AND an extra @ServiceContract interface → FAILED")
    void handlerWithExtraServiceContractInterface_fails() {
        JavaFileObject otherContract = SourceFiles.inline("com.example.OtherService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "other-service", namespace = "integration")
                public interface OtherService {
                    @ServiceOperation("do-other")
                    Future<String> doOther();
                }
                """);

        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                // Implements ServiceHandler<UserService> AND an extra @ServiceContract interface
                public class UserServiceHandler implements ServiceHandler<UserService>, OtherService {
                    @Inject UserServiceHandler() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                    @Override public Future<String> doOther() {
                        return Future.succeededFuture("other");
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, otherContract, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources).assertFailed();
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
