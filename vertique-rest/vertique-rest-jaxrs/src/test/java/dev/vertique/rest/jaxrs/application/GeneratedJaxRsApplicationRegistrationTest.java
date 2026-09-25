// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Application;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves {@code GeneratedJaxRsApplicationRegistration.of(...)}'s C-TYPES {@code null} checks and
 * PP-002 normalized-path backstop (TP-017), reusing C-PATH's accepted and rejected corpus (plan §
 * C-PATH step 3) as the test data.
 *
 * <p>Every row's factory is a {@link CountingFactory} whose invocation count must stay {@code 0}:
 * {@code of(...)} never calls {@code get()} or {@code create()} (AC-009.1).
 */
class GeneratedJaxRsApplicationRegistrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratedJaxRsApplicationRegistrationTest.class);

    /** Minimal {@link Application} type: TP-017 exercises only the registration factory, never composition. */
    static class SampleRegistrationApplication extends Application {}

    private static final Class<SampleRegistrationApplication> APPLICATION_TYPE = SampleRegistrationApplication.class;

    /** A {@code Provider} that counts its invocations, so a test can assert {@code of(...)} never calls it. */
    private static final class CountingFactory implements Provider<SampleRegistrationApplication> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public SampleRegistrationApplication get() {
            calls.incrementAndGet();
            return new SampleRegistrationApplication();
        }

        int calls() {
            return calls.get();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registrationCases")
    @DisplayName(
            "of(...) accepts C-PATH-normalized paths unchanged, rejects every other path with IllegalArgumentException naming the application class and the rejected path, rejects any null argument with NullPointerException, and never invokes the factory")
    void factoryRejectsNonNormalizedPaths(RegistrationCase testCase) {
        testCase.verification().run();
    }

    /** One TP-017 row: a display name and the full verification (action plus assertions) it runs. */
    private record RegistrationCase(String name, Runnable verification) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * TP-017's 21 rows: 4 accepted paths, 14 rejected paths (C-PATH's step-3 corpus), and 3
     * {@code null}-argument cases, in the contract's order.
     *
     * @return the TP-017 rows
     */
    private static Stream<RegistrationCase> registrationCases() {
        return Stream.of(
                accepted("/"),
                accepted("/api"),
                accepted("/api/v1.0"),
                accepted("/a-b_c/~d"),
                rejected("api"),
                rejected("/api/"),
                rejected("/api/*"),
                rejected("/api/:id"),
                rejected("/api/{v}"),
                rejected("/api?x=1"),
                rejected("/api#f"),
                rejected("/api//v1"),
                rejected("/api/../x"),
                rejected("/api/./x"),
                rejected("/api%2Fv1"),
                rejected("/api%20x"),
                rejected("/api/a b"),
                rejected(""),
                nullType(),
                nullPath(),
                nullFactory());
    }

    /**
     * Builds an accepted-path row: {@code of(...)} must not throw, {@code path()} must return the
     * argument unchanged, and the factory must never be invoked.
     *
     * @param path the C-PATH-normalized path expected to be accepted
     * @return the row
     */
    private static RegistrationCase accepted(String path) {
        return new RegistrationCase("accepted: '" + path + "'", () -> {
            CountingFactory factory = new CountingFactory();
            GeneratedJaxRsApplicationRegistration registration = assertDoesNotThrow(
                    () -> GeneratedJaxRsApplicationRegistration.of(APPLICATION_TYPE, path, true, factory),
                    () -> "path '" + path + "' must be accepted");
            assertEquals(path, registration.path(), "path() must return the argument unchanged");
            assertEquals(0, factory.calls(), "of(...) must never invoke the factory");
        });
    }

    /**
     * Builds a rejected-path row: {@code of(...)} must throw {@link IllegalArgumentException} whose
     * message names the application class and the rejected path, and the factory must never be
     * invoked.
     *
     * @param path the non-normalized path expected to be rejected
     * @return the row
     */
    private static RegistrationCase rejected(String path) {
        return new RegistrationCase("rejected: '" + path + "'", () -> {
            CountingFactory factory = new CountingFactory();
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> GeneratedJaxRsApplicationRegistration.of(APPLICATION_TYPE, path, true, factory),
                    () -> "path '" + path + "' must be rejected");
            LOG.info("TP-017 rejected '{}': {}", path, ex.getMessage());
            assertTrue(
                    ex.getMessage().contains(APPLICATION_TYPE.getSimpleName()),
                    () -> "message must name the application class: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(path), () -> "message must name the rejected path: " + ex.getMessage());
            assertEquals(0, factory.calls(), "of(...) must never invoke the factory");
        });
    }

    /**
     * Builds the {@code null} type row: {@code of(...)} must throw {@link NullPointerException}, and
     * the factory must never be invoked.
     *
     * @return the row
     */
    private static RegistrationCase nullType() {
        return new RegistrationCase("null type", () -> {
            CountingFactory factory = new CountingFactory();
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> GeneratedJaxRsApplicationRegistration.of(null, "/valid", true, factory),
                    "a null type must throw NullPointerException");
            LOG.info("TP-017 null type: {}", ex.getMessage());
            assertEquals(0, factory.calls(), "of(...) must never invoke the factory");
        });
    }

    /**
     * Builds the {@code null} path row: {@code of(...)} must throw {@link NullPointerException}, and
     * the factory must never be invoked.
     *
     * @return the row
     */
    private static RegistrationCase nullPath() {
        return new RegistrationCase("null path", () -> {
            CountingFactory factory = new CountingFactory();
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> GeneratedJaxRsApplicationRegistration.of(APPLICATION_TYPE, null, true, factory),
                    "a null path must throw NullPointerException");
            LOG.info("TP-017 null path: {}", ex.getMessage());
            assertEquals(0, factory.calls(), "of(...) must never invoke the factory");
        });
    }

    /**
     * Builds the {@code null} factory row: {@code of(...)} must throw {@link NullPointerException}.
     * There is no factory instance here to assert an invocation count against.
     *
     * @return the row
     */
    private static RegistrationCase nullFactory() {
        return new RegistrationCase("null factory", () -> {
            NullPointerException ex = assertThrows(
                    NullPointerException.class,
                    () -> GeneratedJaxRsApplicationRegistration.of(APPLICATION_TYPE, "/valid", true, null),
                    "a null factory must throw NullPointerException");
            LOG.info("TP-017 null factory: {}", ex.getMessage());
        });
    }
}
