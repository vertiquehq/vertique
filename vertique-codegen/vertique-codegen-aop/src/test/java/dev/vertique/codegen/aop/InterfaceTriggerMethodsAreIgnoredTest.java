// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proof that trigger annotations on interface methods do not enter class-proxy generation. */
class InterfaceTriggerMethodsAreIgnoredTest {

    private static final String PROXY_FQN = "com.example.TriggerContract$AopProxy";

    @Test
    @DisplayName("an interface method carrying a trigger compiles without generating a proxy")
    void doesNotProxyAnInterfaceCarryingATriggerAnnotation() {
        var result = ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), triggerContract())
                .assertSuccess();

        assertFalse(
                result.compilation().generatedSourceFile(PROXY_FQN).isPresent(),
                "an interface-hosted trigger must not generate an $AopProxy class");
    }

    private static javax.tools.JavaFileObject interfaceTrigger() {
        return SourceFiles.inline("com.example.InterfaceTrigger", """
                package com.example;
                import dev.vertique.aop.Aspect;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Aspect(ordering = 1000)
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface InterfaceTrigger {}
                """);
    }

    private static javax.tools.JavaFileObject triggerContract() {
        return SourceFiles.inline("com.example.TriggerContract", """
                package com.example;
                import io.vertx.core.Future;
                public interface TriggerContract {
                    @InterfaceTrigger
                    Future<String> work();
                }
                """);
    }
}
