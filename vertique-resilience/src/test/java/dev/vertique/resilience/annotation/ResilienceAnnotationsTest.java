// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.BackoffStrategy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for canonical resilience declaration resolution. */
class ResilienceAnnotationsTest {

    // --- Test fixtures ---

    @Timeout(5000)
    @CircuitBreaker(maxFailures = 3)
    @Retry(maxRetries = 2, retryOn = IllegalStateException.class, abortOn = UnsupportedOperationException.class)
    interface FullyAnnotatedType {
        @Timeout(1000)
        @CircuitBreaker(maxFailures = 1)
        @Retry(
                maxRetries = 5,
                retryOn = {IllegalArgumentException.class, IllegalStateException.class},
                abortOn = {UnsupportedOperationException.class, Error.class})
        void methodOverrides();

        void inheritsFromType();
    }

    interface NoAnnotations {
        void plainMethod();
    }

    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    interface TimeoutOnlyType {
        void inheritsTimeout();

        @Timeout(500)
        void overridesTimeout();
    }

    // --- Interface inheritance fixtures ---

    @Timeout(10000)
    @CircuitBreaker(maxFailures = 7)
    @Retry(maxRetries = 4)
    interface ParentContractWithClassLevelResilience {
        void inheritedFromParent();
    }

    interface ChildContract extends ParentContractWithClassLevelResilience {}

    interface GrandchildContract extends ChildContract {}

    @Timeout(8000)
    interface ParentWithMethodLevelResilience {
        @Retry(maxRetries = 9)
        void methodOnParent();
    }

    interface ChildOfParentWithMethodLevel extends ParentWithMethodLevelResilience {}

    @Timeout(20000)
    interface ChildOverridingClassLevelTimeout extends ParentContractWithClassLevelResilience {}

    @Test
    @DisplayName("NONE has no declarations")
    void noneHasNoDeclarations() {
        assertFalse(ResilienceAnnotations.NONE.hasAny());
        assertTrue(ResilienceAnnotations.NONE.timeout().isEmpty());
        assertTrue(ResilienceAnnotations.NONE.circuitBreaker().isEmpty());
        assertTrue(ResilienceAnnotations.NONE.retry().isEmpty());
    }

    @Test
    @DisplayName("RetryDeclaration snapshots caller-provided retry and abort lists")
    void retryDeclarationSnapshotsCallerProvidedLists() {
        List<Class<? extends Throwable>> retryOn = new ArrayList<>(List.of(IllegalArgumentException.class));
        List<Class<? extends Throwable>> abortOn = new ArrayList<>(List.of(Error.class));
        RetryDeclaration declaration =
                new RetryDeclaration(3, 100L, 2.0, 1000L, BackoffStrategy.Default.class, retryOn, abortOn);

        retryOn.add(IllegalStateException.class);
        abortOn.add(Throwable.class);

        assertEquals(List.of(IllegalArgumentException.class), declaration.retryOn());
        assertEquals(List.of(Error.class), declaration.abortOn());
    }

    @Test
    @DisplayName("RetryDeclaration returns unmodifiable retry and abort lists")
    void retryDeclarationListsAreUnmodifiable() {
        RetryDeclaration declaration = new RetryDeclaration(
                3,
                100L,
                2.0,
                1000L,
                BackoffStrategy.Default.class,
                List.of(IllegalArgumentException.class),
                List.of(Error.class));

        assertThrows(
                UnsupportedOperationException.class, () -> declaration.retryOn().add(IllegalStateException.class));
        assertThrows(
                UnsupportedOperationException.class, () -> declaration.abortOn().add(Throwable.class));
    }

    @Test
    @DisplayName("method declarations override type declarations and snapshot members")
    void methodDeclarationsOverrideTypeDeclarationsAndSnapshotMembers() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("methodOverrides");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(FullyAnnotatedType.class, method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(1000L, resolved.timeout().orElseThrow().value());
        assertTrue(resolved.circuitBreaker().isPresent());
        assertEquals(1, resolved.circuitBreaker().orElseThrow().maxFailures());
        assertTrue(resolved.retry().isPresent());
        assertEquals(5, resolved.retry().orElseThrow().maxRetries());
        assertEquals(
                List.of(IllegalArgumentException.class, IllegalStateException.class),
                resolved.retry().orElseThrow().retryOn());
        assertEquals(
                List.of(UnsupportedOperationException.class, Error.class),
                resolved.retry().orElseThrow().abortOn());
    }

    @Test
    @DisplayName("type declarations are inherited when the method has none")
    void typeDeclarationsAreInheritedWhenMethodHasNone() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("inheritsFromType");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(FullyAnnotatedType.class, method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(5000L, resolved.timeout().orElseThrow().value());
        assertEquals(TimeUnit.MILLISECONDS, resolved.timeout().orElseThrow().unit());
        assertTrue(resolved.circuitBreaker().isPresent());
        assertEquals(3, resolved.circuitBreaker().orElseThrow().maxFailures());
        assertTrue(resolved.retry().isPresent());
        assertEquals(2, resolved.retry().orElseThrow().maxRetries());
        assertEquals(
                List.of(IllegalStateException.class),
                resolved.retry().orElseThrow().retryOn());
        assertEquals(
                List.of(UnsupportedOperationException.class),
                resolved.retry().orElseThrow().abortOn());
    }

    @Test
    @DisplayName("NONE is returned when no annotations are present")
    void returnsNoneWhenNoAnnotationsArePresent() throws Exception {
        Method method = NoAnnotations.class.getMethod("plainMethod");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(NoAnnotations.class, method);

        assertSame(ResilienceAnnotations.NONE, resolved);
        assertFalse(resolved.hasAny());
    }

    @Test
    @DisplayName("resolve(Method) uses the declaring class")
    void resolveMethodUsesDeclaringClass() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("inheritsFromType");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(5000L, resolved.timeout().orElseThrow().value());
    }

    @Test
    @DisplayName("time-unit conversion preserves the declared timeout")
    void timeUnitConversionPreservesDeclaredTimeout() throws Exception {
        Method method = TimeoutOnlyType.class.getMethod("inheritsTimeout");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(TimeoutOnlyType.class, method);

        assertTrue(resolved.timeout().isPresent());
        var timeout = resolved.timeout().orElseThrow();
        assertEquals(2L, timeout.value());
        assertEquals(TimeUnit.SECONDS, timeout.unit());
        assertEquals(2000L, timeout.unit().toMillis(timeout.value()));
    }

    @Test
    @DisplayName("method-level timeout overrides type-level timeout")
    void methodLevelTimeoutOverridesTypeLevelTimeout() throws Exception {
        Method method = TimeoutOnlyType.class.getMethod("overridesTimeout");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(TimeoutOnlyType.class, method);

        assertTrue(resolved.timeout().isPresent());
        var timeout = resolved.timeout().orElseThrow();
        assertEquals(500L, timeout.value());
        assertEquals(TimeUnit.MILLISECONDS, timeout.unit());
    }

    @Test
    @DisplayName("parent-interface class-level resilience is inherited by the child")
    void parentInterfaceClassLevelResilienceIsInheritedByChild() throws Exception {
        Method method = ChildContract.class.getMethod("inheritedFromParent");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(ChildContract.class, method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(10000L, resolved.timeout().orElseThrow().value());
        assertTrue(resolved.circuitBreaker().isPresent());
        assertEquals(7, resolved.circuitBreaker().orElseThrow().maxFailures());
        assertTrue(resolved.retry().isPresent());
        assertEquals(4, resolved.retry().orElseThrow().maxRetries());
    }

    @Test
    @DisplayName("class-level resilience traverses a transitive interface chain")
    void classLevelResilienceTraversesTransitiveInterfaceChain() throws Exception {
        Method method = GrandchildContract.class.getMethod("inheritedFromParent");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(GrandchildContract.class, method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(10000L, resolved.timeout().orElseThrow().value());
    }

    @Test
    @DisplayName("parent-interface method annotations are inherited by the child")
    void parentInterfaceMethodAnnotationsAreInheritedByChild() throws Exception {
        Method method = ChildOfParentWithMethodLevel.class.getMethod("methodOnParent");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(ChildOfParentWithMethodLevel.class, method);

        assertTrue(resolved.hasAny());
        assertTrue(resolved.retry().isPresent());
        assertEquals(9, resolved.retry().orElseThrow().maxRetries());
        assertTrue(resolved.timeout().isPresent());
        assertEquals(8000L, resolved.timeout().orElseThrow().value());
    }

    @Test
    @DisplayName("child class-level timeout takes precedence over the parent")
    void childClassLevelTimeoutTakesPrecedenceOverParent() throws Exception {
        Method method = ChildOverridingClassLevelTimeout.class.getMethod("inheritedFromParent");
        ResilienceAnnotations resolved = ResilienceAnnotations.resolve(ChildOverridingClassLevelTimeout.class, method);

        assertTrue(resolved.timeout().isPresent());
        assertEquals(20000L, resolved.timeout().orElseThrow().value());
        assertTrue(resolved.circuitBreaker().isPresent());
        assertEquals(7, resolved.circuitBreaker().orElseThrow().maxFailures());
    }
}
