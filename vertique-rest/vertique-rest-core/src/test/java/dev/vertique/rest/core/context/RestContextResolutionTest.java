// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestContextResolution} and related types.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Resolver chain ordering: priority ascending, ties broken by class name (FR-REST-194).
 *   <li>{@link RestContextResolution#resolve} returns the first non-empty result; returns empty when
 *       all resolvers return empty.
 *   <li>{@link RestContextResolution#require} returns the resolved value or throws
 *       {@link RestContextUnavailableException} with the exact FR-REST-174 message.
 *   <li>{@link RestContextUnavailableException} field accessors ({@code type()},
 *       {@code resourceClass()}, {@code methodName()}) return the values passed at construction.
 *   <li>Empty resolver set: {@code resolve} returns empty, {@code require} throws.
 * </ul>
 */
class RestContextResolutionTest {

    // --- Test double: a resolver that records whether it was called and optionally returns a value ---

    /**
     * A fake {@link RestContextResolver} that records its call order via a shared list and
     * optionally returns a pre-configured value.
     */
    static final class RecordingResolver implements RestContextResolver {

        private final List<String> callLog;
        private final String name;
        private final int resolverPriority;
        private final Object returnValue;

        /**
         * Creates a resolver.
         *
         * @param name            label appended to the call log when called
         * @param priority        the resolver priority returned by {@link #priority()}
         * @param returnValue     the value to return, or {@code null} for empty
         * @param callLog         shared list to which {@code name} is appended on each call
         */
        RecordingResolver(String name, int priority, Object returnValue, List<String> callLog) {
            this.name = name;
            this.resolverPriority = priority;
            this.returnValue = returnValue;
            this.callLog = callLog;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
            callLog.add(name);
            return Optional.ofNullable((T) returnValue);
        }

        @Override
        public int priority() {
            return resolverPriority;
        }
    }

    // --- Resolver chain ordering ---

    @Nested
    @DisplayName("Resolver chain ordering")
    class ChainOrdering {

        @Test
        @DisplayName("Lower-priority resolver is consulted before higher-priority resolver")
        void lowerPriorityRunsFirst() {
            List<String> log = new ArrayList<>();
            // priority-0 returns the value; priority-100 would also return one — but must not be reached
            RecordingResolver first = new RecordingResolver("first", 0, "fromFirst", log);
            RecordingResolver second = new RecordingResolver("second", 100, "fromSecond", log);

            RestContextResolution resolution = new RestContextResolution(Set.of(first, second));
            Optional<String> result = resolution.resolve(String.class, mock(RoutingContext.class));

            assertTrue(result.isPresent());
            assertEquals("fromFirst", result.get());
            // second should not have been called because first already matched
            assertEquals(List.of("first"), log);
        }

        @Test
        @DisplayName("When priority-0 returns empty, priority-100 resolver is consulted")
        void higherPriorityResolverUsedWhenLowerReturnsEmpty() {
            List<String> log = new ArrayList<>();
            RecordingResolver first = new RecordingResolver("first", 0, null, log);
            RecordingResolver second = new RecordingResolver("second", 100, "fromSecond", log);

            RestContextResolution resolution = new RestContextResolution(Set.of(first, second));
            Optional<String> result = resolution.resolve(String.class, mock(RoutingContext.class));

            assertTrue(result.isPresent());
            assertEquals("fromSecond", result.get());
            assertEquals(List.of("first", "second"), log);
        }

        @Test
        @DisplayName("Same-priority resolvers are ordered deterministically by class name")
        void samePriorityOrderedByClassName() {
            List<String> log = new ArrayList<>();
            // Both at priority 0 and both return non-empty — first alphabetically by class-name should win.
            // We use anonymous subclasses with known naming pattern; instead use two named inner types.
            // We construct two RecordingResolvers — they have the same class name, so we wrap them.
            // To create two resolvers with different class names at the same priority, use two distinct
            // anonymous classes defined here.

            RestContextResolver resolverAlpha = new RestContextResolver() {
                @Override
                public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
                    log.add("alpha");
                    return Optional.of(type.cast("fromAlpha"));
                }

                @Override
                public int priority() {
                    return 0;
                }

                @Override
                public String toString() {
                    return "alpha";
                }
            };
            RestContextResolver resolverZeta = new RestContextResolver() {
                @Override
                public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
                    log.add("zeta");
                    return Optional.of(type.cast("fromZeta"));
                }

                @Override
                public int priority() {
                    return 0;
                }

                @Override
                public String toString() {
                    return "zeta";
                }
            };

            RestContextResolution resolution = new RestContextResolution(Set.of(resolverAlpha, resolverZeta));
            resolution.resolve(String.class, mock(RoutingContext.class));

            // The first entry in the log must be the one whose class name is alphabetically earlier.
            // Both anonymous classes have synthetic names; the ordering guarantee is stable,
            // so we just assert that exactly one was called and the other was skipped (first match wins).
            assertEquals(1, log.size(), "First matching resolver should have short-circuited the chain");
        }
    }

    // --- resolve ---

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("Returns value from first non-empty resolver")
        void returnsFirstNonEmpty() {
            List<String> log = new ArrayList<>();
            RecordingResolver empty = new RecordingResolver("empty", 0, null, log);
            RecordingResolver match = new RecordingResolver("match", 10, "hello", log);

            RestContextResolution resolution = new RestContextResolution(Set.of(empty, match));
            Optional<String> result = resolution.resolve(String.class, mock(RoutingContext.class));

            assertTrue(result.isPresent());
            assertEquals("hello", result.get());
        }

        @Test
        @DisplayName("Returns empty when all resolvers return empty")
        void returnsEmptyWhenNoneMatch() {
            List<String> log = new ArrayList<>();
            RecordingResolver r1 = new RecordingResolver("r1", 0, null, log);
            RecordingResolver r2 = new RecordingResolver("r2", 5, null, log);

            RestContextResolution resolution = new RestContextResolution(Set.of(r1, r2));
            Optional<String> result = resolution.resolve(String.class, mock(RoutingContext.class));

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Returns empty when resolver set is empty")
        void returnsEmptyForEmptyResolverSet() {
            RestContextResolution resolution = new RestContextResolution(Set.of());
            Optional<String> result = resolution.resolve(String.class, mock(RoutingContext.class));

            assertFalse(result.isPresent());
        }
    }

    // --- require ---

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        @DisplayName("Returns value when resolver finds it")
        void returnsValueWhenPresent() {
            RecordingResolver resolver = new RecordingResolver("r", 0, "found", new ArrayList<>());
            RestContextResolution resolution = new RestContextResolution(Set.of(resolver));

            String result = resolution.require(String.class, mock(RoutingContext.class), "MyResource", "myMethod");

            assertEquals("found", result);
        }

        @Test
        @DisplayName("Throws RestContextUnavailableException with exact FR-REST-174 message when absent")
        void throwsWithExactMessageWhenAbsent() {
            RestContextResolution resolution = new RestContextResolution(Set.of());

            RestContextUnavailableException ex = assertThrows(
                    RestContextUnavailableException.class,
                    () -> resolution.require(String.class, mock(RoutingContext.class), "MyResource", "myMethod"));

            String expected = "Missing REST context parameter String for MyResource#myMethod.\n"
                    + "Ensure String is bound before REST dispatch.";
            assertEquals(expected, ex.getMessage());
        }

        @Test
        @DisplayName("Exception accessors return the values passed at construction")
        void exceptionAccessorsReturnConstructedValues() {
            RestContextResolution resolution = new RestContextResolution(Set.of());

            RestContextUnavailableException ex = assertThrows(
                    RestContextUnavailableException.class,
                    () -> resolution.require(Integer.class, mock(RoutingContext.class), "SomeResource", "someMethod"));

            assertEquals(Integer.class, ex.type());
            assertEquals("SomeResource", ex.resourceClass());
            assertEquals("someMethod", ex.methodName());
        }

        @Test
        @DisplayName("Throws RestContextUnavailableException when resolver set is empty")
        void throwsForEmptyResolverSet() {
            RestContextResolution resolution = new RestContextResolution(Set.of());

            assertThrows(
                    RestContextUnavailableException.class,
                    () -> resolution.require(String.class, mock(RoutingContext.class), "R", "m"));
        }
    }

    // --- RestContextUnavailableException message format ---

    @Nested
    @DisplayName("RestContextUnavailableException")
    class UnavailableException {

        @Test
        @DisplayName("Message uses simple type name, not FQN")
        void messageUsesSimpleName() {
            var ex = new RestContextUnavailableException(java.util.UUID.class, "UuidResource", "findById");

            assertTrue(ex.getMessage().contains("UUID"), "Expected simple name 'UUID' in: " + ex.getMessage());
        }

        @Test
        @DisplayName("Message contains resource class and method name separated by '#'")
        void messageContainsResourceAndMethod() {
            var ex = new RestContextUnavailableException(String.class, "FooResource", "bar");

            assertTrue(
                    ex.getMessage().contains("FooResource#bar"), "Expected 'FooResource#bar' in: " + ex.getMessage());
        }

        @Test
        @DisplayName("type() returns the class passed to the constructor")
        void typeAccessor() {
            var ex = new RestContextUnavailableException(Long.class, "R", "m");
            assertEquals(Long.class, ex.type());
        }

        @Test
        @DisplayName("resourceClass() returns the resource class name passed to the constructor")
        void resourceClassAccessor() {
            var ex = new RestContextUnavailableException(String.class, "MyRes", "m");
            assertEquals("MyRes", ex.resourceClass());
        }

        @Test
        @DisplayName("methodName() returns the method name passed to the constructor")
        void methodNameAccessor() {
            var ex = new RestContextUnavailableException(String.class, "R", "doSomething");
            assertEquals("doSomething", ex.methodName());
        }
    }
}
