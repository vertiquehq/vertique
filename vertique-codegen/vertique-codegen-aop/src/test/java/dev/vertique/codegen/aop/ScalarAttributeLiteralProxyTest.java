// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.codegen.MethodMetadata;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Optional;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proofs that scalar annotation members survive AOP proxy materialization. */
class ScalarAttributeLiteralProxyTest {

    private static final String MARKED_PROXY_FQN = "com.example.DoubleMarked$AopProxy";
    private static final String ASPECT_PROXY_FQN = "com.example.DoubleAspectBean$AopProxy";

    @Test
    @DisplayName("a method annotation carrying a double member is materialized in proxy metadata")
    void proxiesAMethodCarryingADoubleMemberAnnotation() throws Exception {
        var result = ProcessorTestHarness.run(new AopProcessor(), doubleMarker(), doubleMarkedBean())
                .assertSuccess();

        Class<? extends Annotation> annotationType =
                result.loadGeneratedClass("com.example.DoubleMarker").asSubclass(Annotation.class);
        MethodMetadata metadata = methodMetadata(result.loadGeneratedClass(MARKED_PROXY_FQN));
        @SuppressWarnings({"rawtypes", "unchecked"})
        Optional<Annotation> found = metadata.findAnnotation((Class) annotationType);

        assertTrue(found.isPresent(), "the generated metadata must find the runtime-retained double annotation");
        double actual = (double) annotationType.getMethod("value").invoke(found.orElseThrow());
        assertEquals(
                Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits(actual),
                "the generated annotation literal must preserve negative zero");
    }

    @Test
    @DisplayName("an aspect trigger carrying a double member compiles and generates a proxy")
    void anAspectTriggerWithADoubleMemberCompiles() {
        ProcessorTestHarness.run(new AopProcessor(), doubleAspect(), doubleAspectBean())
                .assertSuccess()
                .assertGeneratedSourceContains(ASPECT_PROXY_FQN, "extends DoubleAspectBean");
    }

    private static MethodMetadata methodMetadata(Class<?> proxyType) throws IllegalAccessException {
        Field field = java.util.Arrays.stream(proxyType.getDeclaredFields())
                .filter(candidate -> candidate.getName().endsWith("_META"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("generated proxy has no method metadata field"));
        field.setAccessible(true);
        return (MethodMetadata) field.get(null);
    }

    private static JavaFileObject doubleMarker() {
        return SourceFiles.inline("com.example.DoubleMarker", """
                package com.example;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface DoubleMarker {
                    double value();
                }
                """);
    }

    private static JavaFileObject doubleMarkedBean() {
        return SourceFiles.inline("com.example.DoubleMarked", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import dev.vertique.codegen.aop.TestTimed;
                public class DoubleMarked {
                    @Inject
                    public DoubleMarked() {}
                    @TestTimed
                    @DoubleMarker(-0.0d)
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }

    private static JavaFileObject doubleAspect() {
        return SourceFiles.inline("com.example.DoubleMemberAspect", """
                package com.example;
                import dev.vertique.aop.Aspect;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Aspect(ordering = 1000)
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface DoubleMemberAspect {
                    double value();
                }
                """);
    }

    private static JavaFileObject doubleAspectBean() {
        return SourceFiles.inline("com.example.DoubleAspectBean", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                @SuppressWarnings("unused")
                public class DoubleAspectBean {
                    @Inject
                    public DoubleAspectBean() {}
                    @DoubleMemberAspect(-0.0d)
                    public Future<String> work() {
                        return Future.succeededFuture("done");
                    }
                }
                """);
    }
}
