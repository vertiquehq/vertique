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
 * Verifies that {@link ServiceContractProcessor} enforces that service implementations have exactly
 * one {@code @Inject} constructor.
 *
 * <p>CG-005 is stricter than CG-002: CG-002 silently skips on zero {@code @Inject} constructors
 * (auto-wire opt-in semantics), while CG-005 emits a hard error. This reflects the stronger
 * invariant that every registered service must be DI-manageable.
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Impl with zero {@code @Inject} constructors → ERROR</li>
 *   <li>Impl with two {@code @Inject} constructors → ERROR</li>
 *   <li>Impl with exactly one {@code @Inject} constructor → SUCCESS (positive control)</li>
 * </ul>
 */
class ServiceContractProcessorInjectConstructorTest {

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
    @DisplayName("impl with zero @Inject constructors → FAILED (hard error, not silent skip)")
    void noInjectConstructor_failsWithHardError() {
        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    // No @Inject constructor at all
                    UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("@Inject");
    }

    @Test
    @DisplayName("impl with two @Inject constructors → FAILED")
    void twoInjectConstructors_failsWithError() {
        JavaFileObject injectAnnotation = SourceFiles.inline("jakarta.inject.Inject", """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Target(ElementType.CONSTRUCTOR) @Retention(RetentionPolicy.RUNTIME)
                public @interface Inject {}
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    private final String dep;
                    @Inject UserServiceImpl() { this.dep = null; }
                    @Inject UserServiceImpl(String dep) { this.dep = dep; }
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, injectAnnotation, USER_SERVICE_CONTRACT, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("@Inject");
    }

    @Test
    @DisplayName("impl with exactly one @Inject constructor → SUCCESS (positive control)")
    void oneInjectConstructor_succeeds() {
        JavaFileObject impl = SourceFiles.inline("com.example.UserServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class UserServiceImpl implements UserService {
                    @Inject UserServiceImpl() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture(userId);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, USER_SERVICE_CONTRACT, impl);

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
