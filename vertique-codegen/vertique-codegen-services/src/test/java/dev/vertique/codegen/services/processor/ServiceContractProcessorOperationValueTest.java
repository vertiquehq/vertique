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
 * Verifies that {@link ServiceContractProcessor} rejects contract methods with a blank
 * {@code @ServiceOperation} value.
 *
 * <p>Mirrors {@code dev.vertique.services.OperationIdResolver#resolveStableOperationId}
 * ({@code OperationIdResolver.java:62-67}).
 *
 * <p>A blank value would produce an empty operation id, breaking event bus address construction.
 */
class ServiceContractProcessorOperationValueTest {

    @Test
    @DisplayName("@ServiceOperation(\"\") on a contract method → FAILED with blank value error")
    void blankServiceOperationValue_failsWithBlankValueError() {
        JavaFileObject contract = SourceFiles.inline("com.example.UserService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "user-service", namespace = "integration")
                public interface UserService {
                    @ServiceOperation("")
                    Future<String> getUser(String userId);
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
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);

        ProcessorTestHarness.run(new ServiceContractProcessor(), sources)
                .assertFailed()
                .assertErrorMessage("blank");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
