// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.RetryPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies canonical resilience vocabulary ownership, descriptors, and compatibility boundaries. */
class CanonicalResilienceVocabularyTest {

    @Test
    @DisplayName("only canonical resilience declarations are loadable")
    void ownsOnlyCanonicalDeclarations() throws Exception {
        assertType(
                Timeout.class,
                "dev.vertique.resilience.annotation.Timeout",
                "Ldev/vertique/resilience/annotation/Timeout;");
        assertType(
                Retry.class, "dev.vertique.resilience.annotation.Retry", "Ldev/vertique/resilience/annotation/Retry;");
        assertType(
                CircuitBreaker.class,
                "dev.vertique.resilience.annotation.CircuitBreaker",
                "Ldev/vertique/resilience/annotation/CircuitBreaker;");
        assertType(
                ResilienceAnnotations.class,
                "dev.vertique.resilience.annotation.ResilienceAnnotations",
                "Ldev/vertique/resilience/annotation/ResilienceAnnotations;");
        assertType(
                TimeoutDeclaration.class,
                "dev.vertique.resilience.annotation.TimeoutDeclaration",
                "Ldev/vertique/resilience/annotation/TimeoutDeclaration;");
        assertType(
                RetryDeclaration.class,
                "dev.vertique.resilience.annotation.RetryDeclaration",
                "Ldev/vertique/resilience/annotation/RetryDeclaration;");
        assertType(
                CircuitBreakerDeclaration.class,
                "dev.vertique.resilience.annotation.CircuitBreakerDeclaration",
                "Ldev/vertique/resilience/annotation/CircuitBreakerDeclaration;");
        assertType(
                BackoffStrategy.class,
                "dev.vertique.resilience.BackoffStrategy",
                "Ldev/vertique/resilience/BackoffStrategy;");
        assertType(RetryPolicy.class, "dev.vertique.resilience.RetryPolicy", "Ldev/vertique/resilience/RetryPolicy;");

        assertMethodDescriptor(Timeout.class, "value", "()J");
        assertMethodDescriptor(Timeout.class, "unit", "()Ljava/util/concurrent/TimeUnit;");

        assertMethodDescriptor(Retry.class, "maxRetries", "()I");
        assertMethodDescriptor(Retry.class, "delayMs", "()J");
        assertMethodDescriptor(Retry.class, "backoffMultiplier", "()D");
        assertMethodDescriptor(Retry.class, "maxDelayMs", "()J");
        assertMethodDescriptor(Retry.class, "backoff", "()Ljava/lang/Class;");
        assertMethodDescriptor(Retry.class, "retryOn", "()[Ljava/lang/Class;");
        assertMethodDescriptor(Retry.class, "abortOn", "()[Ljava/lang/Class;");

        assertMethodDescriptor(CircuitBreaker.class, "maxFailures", "()I");
        assertMethodDescriptor(CircuitBreaker.class, "timeoutMs", "()J");
        assertMethodDescriptor(CircuitBreaker.class, "resetTimeoutMs", "()J");

        assertFieldDescriptor(
                ResilienceAnnotations.class, "NONE", "Ldev/vertique/resilience/annotation/ResilienceAnnotations;");
        assertMethodDescriptor(ResilienceAnnotations.class, "timeout", "()Ljava/util/Optional;");
        assertMethodDescriptor(ResilienceAnnotations.class, "circuitBreaker", "()Ljava/util/Optional;");
        assertMethodDescriptor(ResilienceAnnotations.class, "retry", "()Ljava/util/Optional;");
        assertMethodDescriptor(ResilienceAnnotations.class, "hasAny", "()Z");
        assertMethodDescriptor(
                ResilienceAnnotations.class,
                "resolve",
                "(Ljava/lang/Class;Ljava/lang/reflect/Method;)Ldev/vertique/resilience/annotation/ResilienceAnnotations;",
                Class.class,
                Method.class);
        assertMethodDescriptor(
                ResilienceAnnotations.class,
                "resolve",
                "(Ljava/lang/reflect/Method;)Ldev/vertique/resilience/annotation/ResilienceAnnotations;",
                Method.class);

        assertConstructorDescriptor(
                TimeoutDeclaration.class, "(JLjava/util/concurrent/TimeUnit;)V", long.class, TimeUnit.class);
        assertMethodDescriptor(TimeoutDeclaration.class, "value", "()J");
        assertMethodDescriptor(TimeoutDeclaration.class, "unit", "()Ljava/util/concurrent/TimeUnit;");

        assertConstructorDescriptor(CircuitBreakerDeclaration.class, "(IJJ)V", int.class, long.class, long.class);
        assertMethodDescriptor(CircuitBreakerDeclaration.class, "maxFailures", "()I");
        assertMethodDescriptor(CircuitBreakerDeclaration.class, "timeoutMs", "()J");
        assertMethodDescriptor(CircuitBreakerDeclaration.class, "resetTimeoutMs", "()J");

        assertConstructorDescriptor(
                RetryDeclaration.class,
                "(IJDJLjava/lang/Class;Ljava/util/List;Ljava/util/List;)V",
                int.class,
                long.class,
                double.class,
                long.class,
                Class.class,
                List.class,
                List.class);
        assertMethodDescriptor(RetryDeclaration.class, "maxRetries", "()I");
        assertMethodDescriptor(RetryDeclaration.class, "delayMs", "()J");
        assertMethodDescriptor(RetryDeclaration.class, "backoffMultiplier", "()D");
        assertMethodDescriptor(RetryDeclaration.class, "maxDelayMs", "()J");
        assertMethodDescriptor(RetryDeclaration.class, "backoffClass", "()Ljava/lang/Class;");
        assertMethodDescriptor(RetryDeclaration.class, "retryOn", "()Ljava/util/List;");
        assertMethodDescriptor(RetryDeclaration.class, "abortOn", "()Ljava/util/List;");

        assertMethodDescriptor(BackoffStrategy.class, "delay", "(I)J", int.class);
        assertMethodDescriptor(
                RetryPolicy.class, "shouldRetry", "(Ljava/lang/Throwable;I)Z", Throwable.class, int.class);

        assertPackageAbsent("dev.vertique.resilience.internal");
        assertPackageAbsent("dev.vertique.resilience.runtime");
    }

    private static void assertType(Class<?> type, String expectedName, String expectedDescriptor) {
        assertEquals(expectedName, type.getName(), "canonical type name");
        assertEquals(expectedDescriptor, descriptor(type), "canonical type descriptor");
        assertEquals(
                expectedName.substring(0, expectedName.lastIndexOf('.')),
                type.getPackageName(),
                "canonical package ownership");
        assertTrue(Modifier.isPublic(type.getModifiers()), "canonical type must be public: " + expectedName);
    }

    private static void assertMethodDescriptor(
            Class<?> owner, String methodName, String expectedDescriptor, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        assertEquals(expectedDescriptor, descriptor(method), owner.getName() + "#" + methodName);
        assertTrue(Modifier.isPublic(method.getModifiers()), "canonical method must be public: " + method);
    }

    private static void assertConstructorDescriptor(
            Class<?> owner, String expectedDescriptor, Class<?>... parameterTypes) throws NoSuchMethodException {
        Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
        assertEquals(expectedDescriptor, descriptor(constructor), owner.getName() + " constructor");
        assertTrue(Modifier.isPublic(constructor.getModifiers()), "canonical constructor must be public");
    }

    private static void assertFieldDescriptor(Class<?> owner, String fieldName, String expectedDescriptor)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(fieldName);
        assertEquals(expectedDescriptor, descriptor(field.getType()), owner.getName() + "#" + fieldName);
        assertTrue(Modifier.isPublic(field.getModifiers()), "canonical field must be public: " + field);
    }

    private static String descriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            descriptor.append(descriptor(parameterType));
        }
        return descriptor.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(Constructor<?> constructor) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : constructor.getParameterTypes()) {
            descriptor.append(descriptor(parameterType));
        }
        return descriptor.append(")V").toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isPrimitive()) {
            return switch (type.getName()) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> throw new AssertionError("unknown primitive: " + type);
            };
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static void assertPackageAbsent(String packageName) throws Exception {
        String resourceName = packageName.replace('.', '/');
        Enumeration<URL> resources =
                CanonicalResilienceVocabularyTest.class.getClassLoader().getResources(resourceName);
        assertFalse(resources.hasMoreElements(), "package must not exist: " + packageName);
    }
}
