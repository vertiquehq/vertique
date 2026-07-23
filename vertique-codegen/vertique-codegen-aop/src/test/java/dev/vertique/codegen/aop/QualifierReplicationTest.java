// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated proxy constructor replicates the bean's super-constructor qualifier
 * annotations verbatim (FR-013-03 / spike V6) so Dagger resolves the same qualified dependency.
 *
 * <p><strong>Red:</strong> the no-op scaffold processor emits no proxy, so no qualified constructor
 * parameter exists and the assertion fails.
 */
class QualifierReplicationTest {

    private static final String PROXY_FQN = "com.example.Greeter$AopProxy";

    @Test
    @DisplayName("a @Named-qualified super-ctor param is copied verbatim onto the generated proxy ctor")
    void namedQualifierOnSuperCtorParamIsCopiedVerbatim() {
        JavaFileObject bean = SourceFiles.inline("com.example.Greeter", """
                package com.example;
                import dev.vertique.codegen.aop.TestTimed;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import jakarta.inject.Named;
                public class Greeter {
                    private final String greeting;
                    @Inject
                    public Greeter(@Named("greeting") String greeting) {
                        this.greeting = greeting;
                    }
                    @TestTimed
                    public Future<String> greet(String name) {
                        return Future.succeededFuture(greeting + " " + name);
                    }
                }
                """);

        ProcessorTestHarness.run(new AopProcessor(), bean)
                .assertSuccess()
                .assertGeneratedSourceContains(PROXY_FQN, "@Named")
                .assertGeneratedSourceContains(PROXY_FQN, "\"greeting\"")
                .assertGeneratedSourceContains(PROXY_FQN, "super(");
    }
}
