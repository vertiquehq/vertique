// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link GeneratedCompanions#instantiate} for the three cases it must handle:
 *
 * <ul>
 *   <li>Companion absent from the classpath → {@link Optional#empty()}, enabling caller fallback.</li>
 *   <li>Companion present and instantiable → the companion is returned, cast to the origin type.</li>
 *   <li>Companion present but constructor throws → {@code onBroken} is invoked and its exception
 *       propagates; the FQN is included in the message.</li>
 *   <li>Companion present but fails to load (throwing static initializer) → {@code onBroken} is
 *       invoked with the {@link LinkageError} as the cause.</li>
 *   <li>Companion present but the wrong type ({@code origin.cast} fails) → {@code onBroken} is
 *       invoked with the {@link ClassCastException} as the cause.</li>
 * </ul>
 *
 * <p>Fixtures are top-level classes so {@link GeneratedNames#companionFqn} can resolve them by
 * name at runtime:
 * <ul>
 *   <li>{@link GeneratedCompanionFixture} / {@link GeneratedCompanionFixture_TestProxy} —
 *       the normal (present, instantiable) path.</li>
 *   <li>{@link GeneratedCompanionsBrokenFixture} / {@link GeneratedCompanionsBrokenFixture_TestProxy} —
 *       the present-but-broken (throwing constructor) path.</li>
 *   <li>{@link GeneratedCompanionsStaticInitFixture} / {@code …_TestProxy} — the present-but-broken
 *       (throwing static initializer → {@link LinkageError}) path.</li>
 *   <li>{@link GeneratedCompanionsWrongTypeFixture} / {@code …_TestProxy} — the present-but-wrong-type
 *       ({@link ClassCastException} from {@code origin.cast}) path.</li>
 *   <li>The absent path uses an arbitrary suffix for which no class exists on the classpath.</li>
 * </ul>
 */
@DisplayName("GeneratedCompanions")
class GeneratedCompanionsTest {

    // --- Tests ---

    @Nested
    @DisplayName("instantiate")
    class Instantiate {

        @Test
        @DisplayName("returns empty when the companion is absent from the classpath")
        void returnsEmptyWhenAbsent() {
            Optional<GeneratedCompanionFixture> result = GeneratedCompanions.instantiate(
                    GeneratedCompanionFixture.class,
                    "_NonExistentSuffix9999",
                    new Class<?>[] {String.class},
                    new Object[] {"arg"},
                    (fqn, e) -> new RuntimeException("broken: " + fqn, e));

            assertFalse(result.isPresent(), "should be empty when no companion class exists");
        }

        @Test
        @DisplayName("returns the instantiated companion when present on the classpath")
        void returnsInstantiatedCompanionWhenPresent() {
            Optional<GeneratedCompanionFixture> result = GeneratedCompanions.instantiate(
                    GeneratedCompanionFixture.class,
                    "_TestProxy",
                    new Class<?>[] {String.class},
                    new Object[] {"arg"},
                    (fqn, e) -> new RuntimeException("broken: " + fqn, e));

            assertTrue(result.isPresent(), "should be present when the companion class exists");
            assertInstanceOf(
                    GeneratedCompanionFixture_TestProxy.class,
                    result.get(),
                    "returned instance should be the companion class");
        }

        @Test
        @DisplayName("companion sentinel value confirms correct instantiation")
        void companionSentinelCorrect() {
            GeneratedCompanionFixture companion = GeneratedCompanions.instantiate(
                            GeneratedCompanionFixture.class,
                            "_TestProxy",
                            new Class<?>[] {String.class},
                            new Object[] {"arg"},
                            (fqn, e) -> new RuntimeException("broken: " + fqn, e))
                    .orElseThrow();

            org.junit.jupiter.api.Assertions.assertEquals(
                    GeneratedCompanionFixture_TestProxy.SENTINEL,
                    companion.marker(),
                    "marker() should return the stand-in sentinel");
        }

        @Test
        @DisplayName("throws onBroken exception when the companion is present but constructor throws")
        void throwsOnBrokenWhenCompanionConstructorThrows() {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedCompanions.instantiate(
                            GeneratedCompanionsBrokenFixture.class,
                            "_TestProxy",
                            new Class<?>[] {String.class},
                            new Object[] {"arg"},
                            (fqn, e) -> new IllegalStateException(
                                    "Generated proxy %s is present but could not be instantiated".formatted(fqn), e)));

            assertTrue(
                    ex.getMessage().contains("GeneratedCompanionsBrokenFixture_TestProxy"),
                    "exception message should contain the companion FQN; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("throws onBroken (not raw LinkageError) when the present companion fails to load")
        void throwsOnBrokenWhenCompanionFailsToLoad() {
            // The companion's static initializer throws, so the eager Class.forName(initialize=true) load step
            // raises a LinkageError (ExceptionInInitializerError / NoClassDefFoundError) — which must be routed
            // to onBroken (present-but-broken), not allowed to escape as a raw JVM error nor treated as absent.
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedCompanions.instantiate(
                            GeneratedCompanionsStaticInitFixture.class,
                            "_TestProxy",
                            new Class<?>[0],
                            new Object[0],
                            (fqn, e) -> new IllegalStateException(
                                    "Generated proxy %s is present but could not be instantiated".formatted(fqn), e)));

            assertTrue(
                    ex.getMessage().contains("GeneratedCompanionsStaticInitFixture_TestProxy"),
                    "exception message should contain the companion FQN; was: " + ex.getMessage());
            assertInstanceOf(
                    LinkageError.class,
                    ex.getCause(),
                    "cause should be the LinkageError from the failed load; was: " + ex.getCause());
        }

        @Test
        @DisplayName("throws onBroken (not raw ClassCastException) when the present companion is the wrong type")
        void throwsOnBrokenWhenCompanionIsWrongType() {
            // The companion class exists and constructs successfully, but does not implement the origin
            // interface, so origin.cast(...) raises a ClassCastException — which must be routed to onBroken
            // (present-but-broken), not allowed to escape raw.
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> GeneratedCompanions.instantiate(
                            GeneratedCompanionsWrongTypeFixture.class,
                            "_TestProxy",
                            new Class<?>[] {String.class},
                            new Object[] {"arg"},
                            (fqn, e) -> new IllegalStateException(
                                    "Generated proxy %s is present but could not be instantiated".formatted(fqn), e)));

            assertTrue(
                    ex.getMessage().contains("GeneratedCompanionsWrongTypeFixture_TestProxy"),
                    "exception message should contain the companion FQN; was: " + ex.getMessage());
            assertInstanceOf(
                    ClassCastException.class,
                    ex.getCause(),
                    "cause should be the ClassCastException from the failed cast; was: " + ex.getCause());
        }

        @Test
        @DisplayName("onBroken exception carries the underlying cause")
        void onBrokenExceptionCarriesUnderlyingCause() {
            RuntimeException ex = assertThrows(
                    RuntimeException.class,
                    () -> GeneratedCompanions.instantiate(
                            GeneratedCompanionsBrokenFixture.class,
                            "_TestProxy",
                            new Class<?>[] {String.class},
                            new Object[] {"arg"},
                            (fqn, e) -> new RuntimeException("broken: " + fqn, e)));

            org.junit.jupiter.api.Assertions.assertNotNull(
                    ex.getCause(), "onBroken exception should carry the underlying cause");
        }
    }
}
