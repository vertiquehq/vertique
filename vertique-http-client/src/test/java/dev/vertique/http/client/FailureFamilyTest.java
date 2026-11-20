// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpClientClosedException;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.exception.HttpClientTechnicalException;
import dev.vertique.http.client.exception.HttpCredentialException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpExchangeCancelledException;
import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.exception.SseProtocolException;
import dev.vertique.resilience.RetryDelayHint;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the failure family: placement on the module roots, constant secret-free messages,
 * the nested enums and null-argument rejection.
 *
 * <p>Constructor signatures are not frozen by the contract, so the tests build every exception
 * through its public constructors with arguments synthesized by parameter type, each carrying the
 * secret marker.
 */
class FailureFamilyTest {

    private static final String SECRET = "s3cret";
    private static final String PACKAGE = "dev.vertique.http.client.exception";

    private static final List<Class<? extends RuntimeException>> ROOTS =
            List.of(HttpClientTechnicalException.class, HttpClientConfigurationException.class);

    private static final List<Class<? extends RuntimeException>> LEAVES = List.of(
            HttpEgressDeniedException.class,
            HttpCredentialException.class,
            HttpBodyLimitException.class,
            HttpStatusRejectedException.class,
            SseProtocolException.class,
            HttpExchangeCancelledException.class,
            HttpClientClosedException.class);

    // --- synthesis of constructor arguments ---

    private static Object argument(Type type, String secret) {
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        if (raw == String.class) {
            return secret;
        }
        if (raw == int.class || raw == Integer.class) {
            return 503;
        }
        if (raw == long.class || raw == Long.class) {
            return 503L;
        }
        if (raw == boolean.class) {
            return false;
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (Throwable.class.isAssignableFrom(raw)) {
            return new IllegalStateException(secret);
        }
        if (raw == MultiMap.class) {
            return MultiMap.caseInsensitiveMultiMap()
                    .add("X-Key", secret)
                    .add("Set-Cookie", secret + "=1")
                    .add("Retry-After", "7");
        }
        if (raw == Buffer.class) {
            return Buffer.buffer(secret + "-body");
        }
        if (raw == Duration.class) {
            return Duration.ofSeconds(7);
        }
        if (raw == Class.class) {
            return IllegalStateException.class;
        }
        if (raw == Optional.class) {
            Type inner = ((ParameterizedType) type).getActualTypeArguments()[0];
            Type innerType = inner instanceof java.lang.reflect.WildcardType w ? w.getUpperBounds()[0] : inner;
            return Optional.of(argument(innerType, secret));
        }
        throw new AssertionError("test cannot synthesize an argument of type " + type.getTypeName());
    }

    private static Object[] arguments(Constructor<?> constructor, String secret) {
        Type[] types = constructor.getGenericParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = argument(types[i], secret);
        }
        return args;
    }

    private static RuntimeException construct(Constructor<?> constructor, Object[] args) {
        try {
            return (RuntimeException) constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new AssertionError("constructor " + constructor + " threw " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + constructor, e);
        }
    }

    private static List<Constructor<?>> publicConstructors(Class<?> type) {
        List<Constructor<?>> constructors =
                Arrays.stream(type.getConstructors()).collect(Collectors.toList());
        assertThat(constructors)
                .as("public constructors of %s", type.getSimpleName())
                .isNotEmpty();
        return constructors;
    }

    private static Set<String> exceptionPackageClassNames() throws Exception {
        Path root = Paths.get(HttpClientConfigurationException.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path dir = root.resolve(PACKAGE.replace('.', '/'));
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".class") && !n.contains("$") && !n.equals("package-info.class"))
                    .map(n -> PACKAGE + "." + n.substring(0, n.length() - ".class".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    // --- tests ---

    @Test
    @DisplayName("AC-012: every failure is typed on a module root and carries no secret in its message")
    void everyFailureIsTypedOnAModuleRootAndCarriesNoSecretsInItsMessage() throws Exception {
        // Given the four module roots and seven leaves of the contract

        // When the exception package is listed and each root's and leaf's superclass is read
        Set<String> expected = new TreeSet<>();
        Stream.concat(ROOTS.stream(), LEAVES.stream()).forEach(t -> expected.add(t.getName()));

        // Then the package holds exactly those nine types, none public-extends RuntimeException directly,
        // each root extends its core semantic root and each leaf extends a module root or a core root
        assertThat(exceptionPackageClassNames()).isEqualTo(expected);
        assertThat(HttpClientTechnicalException.class.getSuperclass()).isEqualTo(TechnicalException.class);
        assertThat(HttpClientConfigurationException.class.getSuperclass()).isEqualTo(ConfigurationException.class);
        for (Class<? extends RuntimeException> root : ROOTS.subList(0, 1)) {
            assertThat(Modifier.isAbstract(root.getModifiers()))
                    .as("%s is abstract", root.getSimpleName())
                    .isTrue();
        }
        for (Class<? extends RuntimeException> type : LEAVES) {
            assertThat(type.getSuperclass())
                    .as("superclass of %s", type.getSimpleName())
                    .isIn(Stream.concat(
                                    ROOTS.stream(), Stream.of(UnavailableException.class, ValidationException.class))
                            .toList());
            assertThat(Modifier.isAbstract(type.getModifiers())).isFalse();
        }
        for (Class<? extends RuntimeException> type :
                expected.stream().map(FailureFamilyTest::load).toList()) {
            assertThat(type.getSuperclass()).isNotEqualTo(RuntimeException.class);
        }
        assertThat(HttpClientClosedException.class.getSuperclass()).isEqualTo(UnavailableException.class);
        assertThat(HttpEgressDeniedException.class.getSuperclass()).isEqualTo(ValidationException.class);
        assertThat(HttpStatusRejectedException.class.getSuperclass()).isEqualTo(HttpClientTechnicalException.class);
        assertThat(RetryDelayHint.class).isAssignableFrom(HttpStatusRejectedException.class);

        // And every constructor builds a message that is constant and free of the secret marker
        for (Class<? extends RuntimeException> type : LEAVES) {
            for (Constructor<?> constructor : publicConstructors(type)) {
                RuntimeException withSecret = construct(constructor, arguments(constructor, SECRET));
                RuntimeException withOther = construct(constructor, arguments(constructor, "other-value"));
                assertThat(withSecret.getMessage())
                        .as("message of %s", constructor)
                        .isNotNull();
                assertThat(withSecret.getMessage()).doesNotContain(SECRET).doesNotContain("other-value");
                assertThat(withSecret.toString()).doesNotContain(SECRET).doesNotContain("X-Key");
                assertThat(withSecret.getMessage())
                        .as("message of %s is constant", constructor)
                        .isEqualTo(withOther.getMessage());
                assertThat(withSecret.getCause()).as("cause of %s", constructor).isNull();
                assertThat(withSecret.getSuppressed()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("AC-012: the credential failure keeps no cause and names only the cause type")
    void theCredentialFailureKeepsNoCauseAndNamesOnlyTheCauseType() {
        // Given every public constructor of the credential failure, fed a throwable carrying the secret

        for (Constructor<?> constructor : publicConstructors(HttpCredentialException.class)) {
            // When it is built
            HttpCredentialException failure =
                    (HttpCredentialException) construct(constructor, arguments(constructor, SECRET));

            // Then getCause() is null and causeType() carries no secret
            assertThat(failure.getCause()).isNull();
            if (constructor.getParameterCount() == 0) {
                assertThat(failure.causeType()).isEmpty();
            } else {
                assertThat(failure.causeType()).contains("IllegalStateException");
            }
        }
    }

    @Test
    @DisplayName("FR-005: the status rejection copies headers without Set-Cookie and implements the delay hint")
    void theStatusRejectionCopiesHeadersWithoutSetCookieAndImplementsTheDelayHint() {
        for (Constructor<?> constructor : publicConstructors(HttpStatusRejectedException.class)) {
            // Given a rejection built from a header map holding X-Key and Set-Cookie
            Object[] args = arguments(constructor, SECRET);
            MultiMap source = (MultiMap) Arrays.stream(args)
                    .filter(MultiMap.class::isInstance)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("constructor takes no headers: " + constructor));
            HttpStatusRejectedException failure = (HttpStatusRejectedException) construct(constructor, args);

            // When the caller reads headers() and then the source map changes
            MultiMap headers = failure.headers();
            source.add("X-Late", "late");

            // Then it holds X-Key but no Set-Cookie, is a mutable copy and is unaffected by the source map
            assertThat(failure.statusCode()).isEqualTo(503);
            assertThat(headers.get("X-Key")).isEqualTo(SECRET);
            assertThat(headers.getAll("Set-Cookie")).isEmpty();
            assertThat(headers.contains("X-Late")).isFalse();
            headers.add("X-New", "v");
            assertThat(failure.headers().contains("X-New")).isFalse();
            assertThat(failure.bodyExcerpt()).isNotNull();
            failure.bodyExcerpt().ifPresent(first -> {
                byte[] before = first.getBytes();
                first.setByte(0, (byte) (before[0] + 1));
                assertThat(failure.bodyExcerpt().orElseThrow().getBytes()).isEqualTo(before);
            });
            assertThat(failure.retryAfter()).isNotNull();
        }
    }

    @Test
    @DisplayName("FR-005: enums carry the contract constants and accessors reject null arguments")
    void enumsCarryTheContractConstantsAndAccessorsRejectNullArguments() throws Exception {
        // Given the two nested enums of the contract

        // When their constants are read by name

        // Then the sets are exactly the contract constants
        assertThat(names(HttpEgressDeniedException.Reason.values()))
                .containsExactlyInAnyOrder(
                        "NO_HOST",
                        "USER_INFO",
                        "SCHEME",
                        "HOST",
                        "CONNECT_ADDRESS",
                        "VIRTUAL_HOST",
                        "PROXY",
                        "PLAIN_HTTP_CREDENTIALS");
        assertThat(names(HttpBodyLimitException.Direction.values()))
                .containsExactlyInAnyOrder("REQUEST", "RESPONSE", "EVENT");

        // And each accessor returns what its constructor was given, for every constant
        for (Constructor<?> constructor : publicConstructors(HttpEgressDeniedException.class)) {
            for (HttpEgressDeniedException.Reason reason : HttpEgressDeniedException.Reason.values()) {
                Object[] args = arguments(constructor, SECRET);
                replaceEnum(constructor, args, reason);
                HttpEgressDeniedException failure = (HttpEgressDeniedException) construct(constructor, args);
                assertThat(failure.reason()).isEqualTo(reason);
            }
        }
        for (Constructor<?> constructor : publicConstructors(HttpBodyLimitException.class)) {
            for (HttpBodyLimitException.Direction direction : HttpBodyLimitException.Direction.values()) {
                Object[] args = arguments(constructor, SECRET);
                replaceEnum(constructor, args, direction);
                HttpBodyLimitException failure = (HttpBodyLimitException) construct(constructor, args);
                assertThat(failure.direction()).isEqualTo(direction);
                assertThat(failure.limitBytes()).isEqualTo(503L);
            }
        }

        // And a null enum or header argument is rejected with a NullPointerException
        List<Class<?>> checked = List.of(
                HttpEgressDeniedException.class, HttpBodyLimitException.class, HttpStatusRejectedException.class);
        for (Class<?> type : checked) {
            for (Constructor<?> constructor : publicConstructors(type)) {
                Class<?>[] parameters = constructor.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (!parameters[i].isEnum() && parameters[i] != MultiMap.class) {
                        continue;
                    }
                    Object[] args = arguments(constructor, SECRET);
                    args[i] = null;
                    assertThatThrownBy(() -> {
                                try {
                                    constructor.newInstance(args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            })
                            .as("%s with null parameter %d", constructor, i)
                            .isInstanceOf(NullPointerException.class);
                }
            }
        }
    }

    private static void replaceEnum(Constructor<?> constructor, Object[] args, Enum<?> value) {
        Class<?>[] parameters = constructor.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == value.getDeclaringClass()) {
                args[i] = value;
            }
        }
    }

    private static List<String> names(Enum<?>[] constants) {
        List<String> names = new ArrayList<>();
        for (Enum<?> constant : constants) {
            names.add(constant.name());
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends RuntimeException> load(String name) {
        try {
            return (Class<? extends RuntimeException>) Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
