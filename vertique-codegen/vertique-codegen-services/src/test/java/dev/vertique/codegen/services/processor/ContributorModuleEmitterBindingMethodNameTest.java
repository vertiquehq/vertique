// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.services.processor.emit.ContributorModuleEmitter;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ContributorModuleEmitter#bindingMethodName(String)} handles plain names,
 * acronym-leading names, and Java keyword collisions correctly.
 *
 * <p>Also exercises the processor end-to-end with an acronym-leading contract name to ensure the
 * generated module uses the correct (non-broken) method name.
 */
class ContributorModuleEmitterBindingMethodNameTest {

    // --- Unit tests for bindingMethodName ---

    @Test
    @DisplayName("plain name: 'UserService' → 'userService'")
    void plainName_decapitalizesFirstChar() {
        assertEquals("userService", ContributorModuleEmitter.bindingMethodName("UserService"));
    }

    @Test
    @DisplayName("acronym-leading name: 'URLService' → 'URLService' (not 'uRLService')")
    void acronymLeadingName_preservesAcronymCase() {
        // Introspector.decapitalize leaves leading-acronym names unchanged
        assertEquals("URLService", ContributorModuleEmitter.bindingMethodName("URLService"));
    }

    @Test
    @DisplayName("keyword collision: 'New' → 'new_' (guarded)")
    void keywordCollision_appendsUnderscore() {
        // Introspector.decapitalize("New") → "new", which is a keyword
        assertEquals("new_", ContributorModuleEmitter.bindingMethodName("New"));
    }

    @Test
    @DisplayName("single-char name: 'X' → 'x'")
    void singleCharName_decapitalizesCorrectly() {
        assertEquals("x", ContributorModuleEmitter.bindingMethodName("X"));
    }

    // --- Integration test: acronym-leading contract name in generated module ---

    @Test
    @DisplayName("acronym-leading contract 'URLService' generates @Provides method named 'URLService'")
    void acronymLeadingContract_generatesCorrectProviderMethodName() {
        JavaFileObject contract = SourceFiles.inline("com.example.URLService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "url-service", namespace = "integration")
                public interface URLService {
                    @ServiceOperation("resolve")
                    Future<String> resolve(String url);
                }
                """);

        JavaFileObject impl = SourceFiles.inline("com.example.URLServiceImpl", """
                package com.example;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                public class URLServiceImpl implements URLService {
                    @Inject URLServiceImpl() {}
                    @Override public Future<String> resolve(String url) {
                        return Future.succeededFuture(url);
                    }
                }
                """);

        JavaFileObject[] sources = concat(FRAMEWORK_SOURCES, contract, impl);
        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // The generated module must contain a method named 'URLService', not 'uRLService'
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "URLService(");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
