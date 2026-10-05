// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.aop;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that aspect triggers on interface methods weave onto concrete implementing classes
 * (including inherited {@code default} methods), and that an interface alone never becomes a
 * subclass-proxy bean.
 */
class InterfaceTriggerMethodWeaveTest {

    private static final String IMPL_PROXY_FQN = "com.example.UserResource$AopProxy";

    @Test
    @DisplayName("an interface-only trigger compiles without generating an interface $AopProxy")
    void doesNotProxyAnInterfaceCarryingATriggerAnnotation() {
        var result = ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), triggerContract())
                .assertSuccess();

        assertFalse(
                result.compilation()
                        .generatedSourceFile("com.example.TriggerContract$AopProxy")
                        .isPresent(),
                "an interface-hosted trigger without an implementor must not generate a proxy");
    }

    @Test
    @DisplayName("a default interface method trigger weaves onto the implementing class proxy")
    void weavesDefaultInterfaceMethodOntoImplementor() {
        ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), crudWithDefault(), userResource())
                .assertSuccess()
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "extends UserResource")
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "super.delete(")
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "@Override");
    }

    @Test
    @DisplayName("an abstract interface method trigger weaves onto the implementing class proxy")
    void weavesAbstractInterfaceMethodOntoImplementor() {
        ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), crudAbstract(), userResourceOverride())
                .assertSuccess()
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "extends UserResource")
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "super.delete(")
                .assertGeneratedSourceContains(IMPL_PROXY_FQN, "AspectProvider")
                .assertGeneratedSourceDoesNotContain(IMPL_PROXY_FQN, "new MethodInterceptor[] {}");
    }

    @Test
    @DisplayName("a nested implementor of an annotated interface receives a proxy")
    void weavesOntoNestedImplementor() {
        ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), crudAbstract(), nestedImplementor())
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.Container_Bean$AopProxy", "extends Container.Bean")
                .assertGeneratedSourceContains("com.example.Container_Bean$AopProxy", "super.delete(")
                .assertGeneratedSourceContains("com.example.Container_Bean$AopProxy", "AspectProvider")
                .assertGeneratedSourceDoesNotContain(
                        "com.example.Container_Bean$AopProxy", "new MethodInterceptor[] {}");
    }

    @Test
    @DisplayName("a parameterized interface trigger weaves with specialized member types on the implementor")
    void weavesGenericInterfaceWithSpecializedSignature() {
        ProcessorTestHarness.run(new AopProcessor(), interfaceTrigger(), genericContract(), genericBean())
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.GenericBean$AopProxy", "Future<String> echo(String value)")
                .assertGeneratedSourceContains("com.example.GenericBean$AopProxy", "super.echo(")
                .assertGeneratedSourceContains("com.example.GenericBean$AopProxy", "AspectProvider");
    }

    @Test
    @DisplayName("an inherited interface method type parameter is declared on the proxy override")
    void weavesInheritedMethodTypeParameter() {
        ProcessorTestHarness.run(
                        new AopProcessor(), interfaceTrigger(), methodTypeParamContract(), methodTypeParamBean())
                .assertSuccess()
                .assertGeneratedSourceContains(
                        "com.example.MethodTypeParamBean$AopProxy", "<U> Future<U> echo(U value)")
                .assertGeneratedSourceContains("com.example.MethodTypeParamBean$AopProxy", "super.echo(")
                .assertGeneratedSourceContains("com.example.MethodTypeParamBean$AopProxy", "AspectProvider");
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

    private static javax.tools.JavaFileObject crudWithDefault() {
        return SourceFiles.inline("com.example.Crud", """
                package com.example;
                import io.vertx.core.Future;
                public interface Crud {
                    @InterfaceTrigger
                    default Future<String> delete(String id) {
                        return Future.succeededFuture(id);
                    }
                }
                """);
    }

    private static javax.tools.JavaFileObject crudAbstract() {
        return SourceFiles.inline("com.example.Crud", """
                package com.example;
                import io.vertx.core.Future;
                public interface Crud {
                    @InterfaceTrigger
                    Future<String> delete(String id);
                }
                """);
    }

    private static javax.tools.JavaFileObject userResource() {
        return SourceFiles.inline("com.example.UserResource", """
                package com.example;
                import jakarta.inject.Inject;
                public class UserResource implements Crud {
                    @Inject
                    public UserResource() {}
                }
                """);
    }

    private static javax.tools.JavaFileObject nestedImplementor() {
        return SourceFiles.inline("com.example.Container", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class Container {
                    public static class Bean implements Crud {
                        @Inject
                        public Bean() {}
                        @Override
                        public Future<String> delete(String id) {
                            return Future.succeededFuture(id);
                        }
                    }
                }
                """);
    }

    private static javax.tools.JavaFileObject genericContract() {
        return SourceFiles.inline("com.example.GenericContract", """
                package com.example;
                import io.vertx.core.Future;
                public interface GenericContract<T> {
                    @InterfaceTrigger
                    default Future<T> echo(T value) {
                        return Future.succeededFuture(value);
                    }
                }
                """);
    }

    private static javax.tools.JavaFileObject genericBean() {
        return SourceFiles.inline("com.example.GenericBean", """
                package com.example;
                import jakarta.inject.Inject;
                public class GenericBean implements GenericContract<String> {
                    @Inject
                    public GenericBean() {}
                }
                """);
    }

    private static javax.tools.JavaFileObject methodTypeParamContract() {
        return SourceFiles.inline("com.example.MethodTypeParamContract", """
                package com.example;
                import io.vertx.core.Future;
                public interface MethodTypeParamContract {
                    @InterfaceTrigger
                    default <U> Future<U> echo(U value) {
                        return Future.succeededFuture(value);
                    }
                }
                """);
    }

    private static javax.tools.JavaFileObject methodTypeParamBean() {
        return SourceFiles.inline("com.example.MethodTypeParamBean", """
                package com.example;
                import jakarta.inject.Inject;
                public class MethodTypeParamBean implements MethodTypeParamContract {
                    @Inject
                    public MethodTypeParamBean() {}
                }
                """);
    }

    private static javax.tools.JavaFileObject userResourceOverride() {
        return SourceFiles.inline("com.example.UserResource", """
                package com.example;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                public class UserResource implements Crud {
                    @Inject
                    public UserResource() {}
                    @Override
                    public Future<String> delete(String id) {
                        return Future.succeededFuture(id);
                    }
                }
                """);
    }
}
