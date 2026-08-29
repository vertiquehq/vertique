// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract proofs for structured adapter identity encoding and the redacted common exception
 * hierarchy.
 *
 * <p>The runtime types are intentionally loaded reflectively to keep this proof focused on the
 * frozen public contract while observing the pipeline's package-local derivation seam without
 * turning that seam into public API.
 */
class AdapterOperationIdentityTest {

    private static final String IDENTITY_TYPE = "dev.vertique.resilience.adapter.AdapterOperationIdentity";
    private static final String PIPELINE_TYPE = "dev.vertique.resilience.ResiliencePipeline";
    private static final String APPLICATION_OPERATION_KEY =
            "application:operation:b71ab57bc248a849ca161ebbd468e7a6d7d3af8bdc7f0e1d3490331d3c5926fc";
    private static final String APPLICATION_STATE_KEY =
            "application:state:af7587cbb89ae4ea2241b27f5e686c071fb2abbf49f5b67117008799f51e1d79";
    private static final String RAW_OPERATION = "inventory.read";
    private static final String RAW_SECRET = "callback-secret-value";

    private static final Map<String, String> EXPECTED_DIGESTS = Map.of(
            "service.operation|default|inventory|read",
            "6fe68204f884ba5740cc4498e63604642053075392e3052099c9dd13637d45d9",
            "rest-client.method|client\u0000x|dev.Example|get|java.lang.String|😀",
            "f232501287418790471dfa5a5fb58567bcdd270b8a69c2ae1f8b4150dd07b315",
            "rest-client.interface-circuit|inventory|dev.example.InventoryClient",
            "58b76b5226a3700f508196142a6a68c081dbd2ee9717437fb661ddf03b8aeb4e",
            "service.operation|||",
            "06793a23871ea046af98b7ecfbe06847b47ce79e74a1fe3a533d2cbb91ab9a38",
            "application.operation|inventory.read",
            "b71ab57bc248a849ca161ebbd468e7a6d7d3af8bdc7f0e1d3490331d3c5926fc",
            "application.state|inventory",
            "af7587cbb89ae4ea2241b27f5e686c071fb2abbf49f5b67117008799f51e1d79");

    @Test
    @DisplayName("identity codec matches every normative vector without a public bypass")
    void matchesVectorsWithoutPublicBypass() {
        for (Map.Entry<String, String> vector : EXPECTED_DIGESTS.entrySet()) {
            List<String> fields = Arrays.asList(vector.getKey().split("\\|", -1));
            String kind = fields.getFirst();
            List<String> components = fields.subList(1, fields.size());

            String derivedKey = derive(identity(kind, components));

            assertEquals(
                    kind.replace('.', ':') + ":" + vector.getValue(),
                    derivedKey,
                    "full SHA-256 vector for " + vector.getKey());
            assertTrue(derivedKey.matches("[a-z0-9:-]{1,32}:[0-9a-f]{64}"));
        }

        Class<?> pipeline = load(PIPELINE_TYPE);
        Method derivation = Arrays.stream(pipeline.getDeclaredMethods())
                .filter(method -> method.getName().equals("deriveAdapterOperationKey"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("pipeline identity derivation seam is absent"));
        assertFalse(Modifier.isPublic(derivation.getModifiers()), "identity derivation must remain package-local");
    }

    @Test
    @DisplayName("identity framing accepts empty, NUL, supplementary, and maximum-kind inputs")
    void acceptsBoundaryAndUnicodeComponents() {
        String maximumKind = "a".repeat(32);

        assertNotNull(derive(identity(maximumKind, List.of())));
        assertNotNull(derive(identity("service.operation", List.of("", "", ""))));
        assertNotNull(derive(identity("rest-client.method", List.of("client\u0000x", "😀"))));
    }

    @Test
    @DisplayName("empty programmatic pipelines reject construction and invalid operation names")
    void rejectsEmptyPipelineAndInvalidOperationNames() {
        Class<?> vertxType = load("io.vertx.core.Vertx");
        Object vertx = invokeStatic(vertxType, "vertx");
        Object resilience = null;
        try {
            Class<?> resilienceType = load("dev.vertique.resilience.Resilience");
            resilience = invokeStatic(resilienceType, "create", vertxType, vertx);
            final Object runtime = resilience;

            Object builder = invoke(runtime, "pipeline", String.class, RAW_OPERATION);
            assertThrows(IllegalStateException.class, () -> invoke(builder, "build"));
            assertNotNull(invoke(runtime, "pipeline", String.class, "a".repeat(4096)));
            assertInvocationIllegalArgument(() -> invoke(runtime, "pipeline", String.class, "a".repeat(4097)));
            assertInvocationIllegalArgument(() -> invoke(runtime, "pipeline", String.class, "invalid\uD800"));
        } finally {
            if (resilience != null) {
                awaitClose(resilience);
            }
            awaitClose(vertx);
        }
    }

    @Test
    @DisplayName("identity construction rejects malformed kinds, components, and UTF-16")
    void rejectsMalformedIdentityInputs() {
        assertThrows(NullPointerException.class, () -> identity(null, List.of("component")));
        assertIllegalArgument(() -> identity("", List.of("component")));
        assertIllegalArgument(() -> identity("a".repeat(33), List.of("component")));
        assertIllegalArgument(() -> identity("Service.operation", List.of("component")));
        assertIllegalArgument(() -> identity("service_operation", List.of("component")));
        assertThrows(NullPointerException.class, () -> identity("service.operation", null));
        assertThrows(NullPointerException.class, () -> identity("service.operation", List.of((String) null)));
        assertIllegalArgument(() -> identity("service.operation", List.of("bad\uD800")));
        assertIllegalArgument(() -> identity("service.operation", List.of("bad\uDC00")));
        assertIllegalArgument(() -> identity("bad\uD800", List.of("component")));
    }

    @Test
    @DisplayName("only grammar-valid derived keys may construct runtime exceptions")
    void rejectsMalformedAndRawKeys() {
        List<String> invalidKeys = Arrays.asList(
                null,
                "",
                RAW_OPERATION,
                "application:operation:short",
                "application:operation:" + "a".repeat(63),
                "application:operation:" + "a".repeat(65),
                "Application:operation:" + "a".repeat(64),
                "application_operation:" + "a".repeat(64),
                "application:operation:" + "A".repeat(64),
                "application:operation: " + "a".repeat(63),
                "application:operation:" + "a".repeat(63) + "!");

        for (String invalidKey : invalidKeys) {
            assertIllegalConstructor(
                    "dev.vertique.resilience.exception.ResilienceTimeoutException",
                    new Class<?>[] {String.class, long.class},
                    invalidKey,
                    250L);
            assertIllegalConstructor(
                    "dev.vertique.resilience.exception.CircuitOpenException",
                    new Class<?>[] {String.class, String.class},
                    invalidKey,
                    APPLICATION_STATE_KEY);
        }
    }

    @Test
    @DisplayName("runtime exception constructors and accessors match the frozen public signatures")
    void exceptionConstructorsAndAccessorsMatchContract() throws Exception {
        Class<?> resilienceException = load("dev.vertique.resilience.exception.ResilienceException");
        Class<?> unavailableException = load("dev.vertique.resilience.exception.ResilienceUnavailableException");
        assertTrue(resilienceException.isSealed());
        assertTrue(unavailableException.isSealed());
        assertTrue(Modifier.isAbstract(resilienceException.getModifiers()));
        assertTrue(Modifier.isAbstract(unavailableException.getModifiers()));
        assertTrue(Modifier.isProtected(
                resilienceException.getDeclaredConstructor(String.class).getModifiers()));
        assertTrue(Modifier.isProtected(
                unavailableException.getDeclaredConstructor(String.class).getModifiers()));

        assertPublicConstructor(
                "dev.vertique.resilience.exception.ResilienceTimeoutException", String.class, long.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.ResilienceTimeoutException", 1);
        assertPublicConstructor("dev.vertique.resilience.exception.CircuitOpenException", String.class, String.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.CircuitOpenException", 1);
        assertPublicConstructor("dev.vertique.resilience.exception.BulkheadRejectedException", String.class, int.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.BulkheadRejectedException", 1);
        assertPublicConstructor(
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException", String.class, long.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.BulkheadQueueTimeoutException", 1);
        assertPublicConstructor("dev.vertique.resilience.exception.ResilienceClosedException", String.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.ResilienceClosedException", 1);
        assertPublicConstructor(
                "dev.vertique.resilience.exception.ResiliencePolicyException",
                load("dev.vertique.resilience.exception.ResiliencePolicyFailureReason"));
        assertPublicConstructor(
                "dev.vertique.resilience.exception.ResiliencePolicyException",
                String.class,
                load("dev.vertique.resilience.PolicyCallbackKind"),
                Class.class,
                Optional.class);
        assertPublicConstructorCount("dev.vertique.resilience.exception.ResiliencePolicyException", 2);
        assertEquals(
                Set.of("INVALID_CONFIGURATION", "INCOMPLETE_CONFIGURATION"),
                Arrays.stream(load("dev.vertique.resilience.exception.ResiliencePolicyFailureReason")
                                .getEnumConstants())
                        .map(value -> ((Enum<?>) value).name())
                        .collect(java.util.stream.Collectors.toSet()));

        assertPublicMethod(
                "dev.vertique.resilience.exception.ResilienceTimeoutException", "operationKey", String.class);
        assertPublicMethod("dev.vertique.resilience.exception.ResilienceTimeoutException", "timeoutMs", long.class);
        assertPublicMethod("dev.vertique.resilience.exception.CircuitOpenException", "operationKey", String.class);
        assertPublicMethod("dev.vertique.resilience.exception.CircuitOpenException", "stateKey", String.class);
        assertPublicMethod("dev.vertique.resilience.exception.BulkheadRejectedException", "operationKey", String.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.BulkheadRejectedException", "maxConcurrentCalls", int.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException", "operationKey", String.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException", "queueTimeoutMs", long.class);
        assertPublicMethod("dev.vertique.resilience.exception.ResilienceClosedException", "operationKey", String.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.ResiliencePolicyException", "operationKey", Optional.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.ResiliencePolicyException", "callbackKind", Optional.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.ResiliencePolicyException",
                "callbackExceptionClass",
                Optional.class);
        assertPublicMethod(
                "dev.vertique.resilience.exception.ResiliencePolicyException", "attemptExceptionClass", Optional.class);
    }

    @Test
    @DisplayName("sealed roots contain exactly the complete technical and unavailable hierarchies")
    void sealedExceptionHierarchyIsComplete() {
        Class<?> technicalRoot = load("dev.vertique.resilience.exception.ResilienceException");
        Class<?> unavailableRoot = load("dev.vertique.resilience.exception.ResilienceUnavailableException");

        assertEquals(
                "dev.vertique.core.exception.TechnicalException",
                technicalRoot.getSuperclass().getName());
        assertEquals(
                "dev.vertique.core.exception.UnavailableException",
                unavailableRoot.getSuperclass().getName());
        assertEquals(
                Set.of(
                        load("dev.vertique.resilience.exception.ResilienceTimeoutException"),
                        load("dev.vertique.resilience.exception.ResiliencePolicyException")),
                Set.of(technicalRoot.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        load("dev.vertique.resilience.exception.CircuitOpenException"),
                        load("dev.vertique.resilience.exception.BulkheadRejectedException"),
                        load("dev.vertique.resilience.exception.BulkheadQueueTimeoutException"),
                        load("dev.vertique.resilience.exception.ResilienceClosedException")),
                Set.of(unavailableRoot.getPermittedSubclasses()));

        Map<String, String> expectedParents = Map.of(
                "dev.vertique.resilience.exception.ResilienceTimeoutException", technicalRoot.getName(),
                "dev.vertique.resilience.exception.ResiliencePolicyException", technicalRoot.getName(),
                "dev.vertique.resilience.exception.CircuitOpenException", unavailableRoot.getName(),
                "dev.vertique.resilience.exception.BulkheadRejectedException", unavailableRoot.getName(),
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException", unavailableRoot.getName(),
                "dev.vertique.resilience.exception.ResilienceClosedException", unavailableRoot.getName());
        expectedParents.forEach((typeName, parentName) -> {
            Class<?> type = load(typeName);
            assertTrue(Modifier.isFinal(type.getModifiers()), typeName + " must be final");
            assertEquals(parentName, type.getSuperclass().getName(), typeName + " parent");
        });
    }

    @Test
    @DisplayName("exception messages redact keys, raw names, callback messages, and causes")
    void exceptionMessagesAreRedacted() {
        Object timeout = construct(
                "dev.vertique.resilience.exception.ResilienceTimeoutException",
                new Class<?>[] {String.class, long.class},
                APPLICATION_OPERATION_KEY,
                250L);
        Object circuit = construct(
                "dev.vertique.resilience.exception.CircuitOpenException",
                new Class<?>[] {String.class, String.class},
                APPLICATION_OPERATION_KEY,
                APPLICATION_STATE_KEY);
        Object rejected = construct(
                "dev.vertique.resilience.exception.BulkheadRejectedException",
                new Class<?>[] {String.class, int.class},
                APPLICATION_OPERATION_KEY,
                8);
        Object queueTimeout = construct(
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException",
                new Class<?>[] {String.class, long.class},
                APPLICATION_OPERATION_KEY,
                500L);
        Object closed = construct(
                "dev.vertique.resilience.exception.ResilienceClosedException",
                new Class<?>[] {String.class},
                APPLICATION_OPERATION_KEY);
        Object policy = construct(
                "dev.vertique.resilience.exception.ResiliencePolicyException",
                new Class<?>[] {load("dev.vertique.resilience.exception.ResiliencePolicyFailureReason")},
                enumConstant(
                        "dev.vertique.resilience.exception.ResiliencePolicyFailureReason", "INVALID_CONFIGURATION"));
        Object callbackPolicy = construct(
                "dev.vertique.resilience.exception.ResiliencePolicyException",
                new Class<?>[] {
                    String.class, load("dev.vertique.resilience.PolicyCallbackKind"), Class.class, Optional.class
                },
                APPLICATION_OPERATION_KEY,
                enumConstant("dev.vertique.resilience.PolicyCallbackKind", "FAILURE_RECORDING"),
                SecretCallbackFailure.class,
                Optional.of(SecretAttemptFailure.class));

        for (Object exception : List.of(timeout, circuit, rejected, queueTimeout, closed, policy, callbackPolicy)) {
            String message = ((Throwable) exception).getMessage();
            assertNotNull(message);
            assertFalse(message.contains(APPLICATION_OPERATION_KEY));
            assertFalse(message.contains(APPLICATION_STATE_KEY));
            assertFalse(message.contains(RAW_OPERATION));
            assertFalse(message.contains(RAW_SECRET));
            assertNull(((Throwable) exception).getCause());
            assertEquals(0, ((Throwable) exception).getSuppressed().length);
        }

        assertEquals(Optional.of(APPLICATION_OPERATION_KEY), invoke(callbackPolicy, "operationKey"));
        assertEquals(
                Optional.of(enumConstant("dev.vertique.resilience.PolicyCallbackKind", "FAILURE_RECORDING")),
                invoke(callbackPolicy, "callbackKind"));
        assertEquals(
                Optional.of(SecretCallbackFailure.class.getName()), invoke(callbackPolicy, "callbackExceptionClass"));
        assertEquals(
                Optional.of(SecretAttemptFailure.class.getName()), invoke(callbackPolicy, "attemptExceptionClass"));
    }

    /** A callback failure whose message must never enter a policy exception. */
    static final class SecretCallbackFailure extends RuntimeException {
        SecretCallbackFailure() {
            super(RAW_SECRET);
        }
    }

    /** An attempt failure whose message must never enter a policy exception. */
    static final class SecretAttemptFailure extends RuntimeException {
        SecretAttemptFailure() {
            super("attempt-secret-value");
        }
    }

    private static Object identity(String kind, List<String> components) {
        try {
            Class<?> identityType = load(IDENTITY_TYPE);
            return identityType.getConstructor(String.class, List.class).newInstance(kind, components);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + IDENTITY_TYPE, e);
        }
    }

    private static String derive(Object identity) {
        Class<?> pipeline = load(PIPELINE_TYPE);
        Method derivation = Arrays.stream(pipeline.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == String.class)
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0] == identity.getClass())
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("pipeline must expose one package-local identity derivation method"));
        assertFalse(Modifier.isPublic(derivation.getModifiers()), "identity derivation must not be public");
        try {
            derivation.setAccessible(true);
            return (String) derivation.invoke(null, identity);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke package-local codec", e);
        }
    }

    private static void assertIllegalConstructor(String typeName, Class<?>[] parameterTypes, Object... arguments) {
        assertIllegalArgument(() -> construct(typeName, parameterTypes, arguments));
    }

    private static void assertPublicConstructor(String typeName, Class<?>... parameterTypes) throws Exception {
        Constructor<?> constructor = load(typeName).getDeclaredConstructor(parameterTypes);
        assertTrue(Modifier.isPublic(constructor.getModifiers()), typeName + " constructor must be public");
    }

    private static void assertPublicConstructorCount(String typeName, int expectedCount) {
        long actualCount = Arrays.stream(load(typeName).getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .count();
        assertEquals(expectedCount, actualCount, typeName + " public constructor count");
    }

    private static void assertPublicMethod(
            String typeName, String methodName, Class<?> returnType, Class<?>... parameters) throws Exception {
        Method method = load(typeName).getDeclaredMethod(methodName, parameters);
        assertEquals(returnType, method.getReturnType(), typeName + "#" + methodName + " return type");
        assertTrue(Modifier.isPublic(method.getModifiers()), typeName + "#" + methodName + " must be public");
    }

    private static Object construct(String typeName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return load(typeName).getConstructor(parameterTypes).newInstance(arguments);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct " + typeName, e);
        }
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?> parameterType, Object argument) {
        return invoke(target, methodName, new Class<?>[] {parameterType}, argument);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName, e);
        }
    }

    private static Object invokeStatic(Class<?> type, String methodName, Class<?> parameterType, Object argument) {
        try {
            return type.getMethod(methodName, parameterType).invoke(null, argument);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + type.getName() + "#" + methodName, e);
        }
    }

    private static Object invokeStatic(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName).invoke(null);
        } catch (InvocationTargetException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + type.getName() + "#" + methodName, e);
        }
    }

    private static void awaitClose(Object owner) {
        Object future = invoke(owner, "close");
        Object completionStage = invoke(future, "toCompletionStage");
        Object completableFuture = invoke(completionStage, "toCompletableFuture");
        invoke(completableFuture, "join");
    }

    private static void assertInvocationIllegalArgument(Runnable action) {
        assertIllegalArgument(action);
    }

    private static Object enumConstant(String typeName, String constantName) {
        return Arrays.stream(load(typeName).getEnumConstants())
                .filter(constant -> ((Enum<?>) constant).name().equals(constantName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing enum constant " + typeName + "." + constantName));
    }

    private static Class<?> load(String typeName) {
        try {
            return Class.forName(typeName, false, AdapterOperationIdentityTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Required T011 type is absent: " + typeName, e);
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new AssertionError(throwable);
    }
}
