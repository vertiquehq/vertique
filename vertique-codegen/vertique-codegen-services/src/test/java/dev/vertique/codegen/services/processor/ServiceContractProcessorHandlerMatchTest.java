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
 * Verifies that {@link ServiceContractProcessor} enforces handler-to-contract method matching.
 *
 * <p>Mirrors {@code dev.vertique.services.MethodValidator#validateHandlerMethods}
 * ({@code MethodValidator.java:54-160}).
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Handler has no method matching a contract method name → FAILED.</li>
 *   <li>Handler method has a mismatched payload type → FAILED.</li>
 *   <li>Handler method has an extra param that is neither {@code SecurityContext} nor
 *       {@code @DispatchContextValue}-annotated → FAILED.</li>
 * </ul>
 */
class ServiceContractProcessorHandlerMatchTest {

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
    @DisplayName("handler has no method matching contract method name → FAILED")
    void handlerMissingMatchingMethod_fails() {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    // No getUser method — completely missing
                    public Future<String> fetchUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("getUser");
    }

    @Test
    @DisplayName("handler method has mismatched payload type → FAILED")
    void handlerMethodMismatchedPayloadType_fails() {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    // Contract declares getUser(String) but handler declares getUser(Integer)
                    public Future<String> getUser(Integer userId) {
                        return Future.succeededFuture(userId.toString());
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources).assertFailed();
    }

    @Test
    @DisplayName("handler method has extra non-context param → FAILED with targeted param+type diagnostic")
    void handlerMethodExtraNonContextParam_fails() {
        JavaFileObject handler = SourceFiles.inline("com.example.UserServiceHandler", """
                package com.example;
                import dev.vertique.services.ServiceHandler;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceHandler implements ServiceHandler<UserService> {
                    @Inject UserServiceHandler() {}
                    // Extra param "extra" is neither SecurityContext nor @DispatchContextValue
                    public Future<String> getUser(String userId, String extra) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, handler);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                // Targeted diagnostic must name the offending param and suggest the two valid kinds
                .assertErrorMessage("extra")
                .assertErrorMessage("@DispatchContextValue");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
