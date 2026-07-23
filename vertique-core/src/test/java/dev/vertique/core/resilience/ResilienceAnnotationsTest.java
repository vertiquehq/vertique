// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResilienceAnnotations} resolution logic.
 */
class ResilienceAnnotationsTest {

    // --- Test Fixtures ---

    @Timeout(5000)
    @CircuitBreaker(maxFailures = 3)
    @Retry(maxRetries = 2)
    interface FullyAnnotatedType {
        @Timeout(1000)
        @CircuitBreaker(maxFailures = 1)
        @Retry(maxRetries = 5)
        Future<String> methodOverrides();

        Future<String> inheritsFromType();
    }

    interface NoAnnotations {
        Future<String> plainMethod();
    }

    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    interface TimeoutOnlyType {
        Future<String> inheritsTimeout();

        @Timeout(500)
        Future<String> overridesTimeout();
    }

    // --- Inheritance fixtures ---

    @Timeout(10000)
    @CircuitBreaker(maxFailures = 7)
    @Retry(maxRetries = 4)
    interface ParentContractWithClassLevelResilience {
        Future<String> inheritedFromParent();
    }

    interface ChildContract extends ParentContractWithClassLevelResilience {}

    interface GrandchildContract extends ChildContract {}

    @Timeout(8000)
    interface ParentWithMethodLevelResilience {
        @Retry(maxRetries = 9)
        Future<String> methodOnParent();
    }

    interface ChildOfParentWithMethodLevel extends ParentWithMethodLevelResilience {}

    @Timeout(20000)
    interface ChildOverridingClassLevelTimeout extends ParentContractWithClassLevelResilience {}

    // --- Tests ---

    @Test
    @DisplayName("NONE constant has all null fields and hasAny returns false")
    void noneShouldHaveAllNullFields() {
        assertNull(ResilienceAnnotations.NONE.timeout());
        assertNull(ResilienceAnnotations.NONE.circuitBreaker());
        assertNull(ResilienceAnnotations.NONE.retry());
        assertFalse(ResilienceAnnotations.NONE.hasAny());
    }

    @Test
    @DisplayName("Method-level annotations override type-level for all three")
    void shouldOverrideTypeLevel() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("methodOverrides");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(FullyAnnotatedType.class, method);

        assertTrue(annotations.hasAny());
        assertNotNull(annotations.timeout());
        assertEquals(1000L, annotations.timeout().value());
        assertNotNull(annotations.circuitBreaker());
        assertEquals(1, annotations.circuitBreaker().maxFailures());
        assertNotNull(annotations.retry());
        assertEquals(5, annotations.retry().maxRetries());
    }

    @Test
    @DisplayName("Type-level annotations inherited when method has none")
    void shouldInheritFromType() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("inheritsFromType");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(FullyAnnotatedType.class, method);

        assertTrue(annotations.hasAny());
        assertNotNull(annotations.timeout());
        assertEquals(5000L, annotations.timeout().value());
        assertNotNull(annotations.circuitBreaker());
        assertEquals(3, annotations.circuitBreaker().maxFailures());
        assertNotNull(annotations.retry());
        assertEquals(2, annotations.retry().maxRetries());
    }

    @Test
    @DisplayName("Returns NONE when no annotations present")
    void shouldReturnNoneWhenNoAnnotations() throws Exception {
        Method method = NoAnnotations.class.getMethod("plainMethod");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(NoAnnotations.class, method);

        assertSame(ResilienceAnnotations.NONE, annotations);
        assertFalse(annotations.hasAny());
    }

    @Test
    @DisplayName("resolve(Method) convenience overload uses declaring class")
    void shouldResolveFromDeclaringClass() throws Exception {
        Method method = FullyAnnotatedType.class.getMethod("inheritsFromType");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(method);

        assertTrue(annotations.hasAny());
        assertEquals(5000L, annotations.timeout().value());
    }

    @Test
    @DisplayName("TimeUnit conversion works for type-level @Timeout")
    void shouldConvertTimeUnit() throws Exception {
        Method method = TimeoutOnlyType.class.getMethod("inheritsTimeout");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(TimeoutOnlyType.class, method);

        assertNotNull(annotations.timeout());
        assertEquals(2L, annotations.timeout().value());
        assertEquals(TimeUnit.SECONDS, annotations.timeout().unit());
        assertEquals(
                2000L,
                annotations.timeout().unit().toMillis(annotations.timeout().value()));
    }

    @Test
    @DisplayName("Method-level @Timeout overrides type-level")
    void shouldOverrideTimeout() throws Exception {
        Method method = TimeoutOnlyType.class.getMethod("overridesTimeout");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(TimeoutOnlyType.class, method);

        assertNotNull(annotations.timeout());
        assertEquals(500L, annotations.timeout().value());
        assertEquals(TimeUnit.MILLISECONDS, annotations.timeout().unit());
    }

    // --- Interface inheritance regression tests ---

    @Test
    @DisplayName("Class-level resilience on parent interface is inherited by child @ServiceContract")
    void classLevelResilienceOnParentInterface_isResolvedFromChild() throws Exception {
        Method method = ChildContract.class.getMethod("inheritedFromParent");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(ChildContract.class, method);

        assertTrue(annotations.hasAny());
        assertNotNull(annotations.timeout());
        assertEquals(10000L, annotations.timeout().value());
        assertNotNull(annotations.circuitBreaker());
        assertEquals(7, annotations.circuitBreaker().maxFailures());
        assertNotNull(annotations.retry());
        assertEquals(4, annotations.retry().maxRetries());
    }

    @Test
    @DisplayName("Class-level resilience traverses transitive interface chain")
    void classLevelResilience_isResolvedFromGrandparent() throws Exception {
        Method method = GrandchildContract.class.getMethod("inheritedFromParent");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(GrandchildContract.class, method);

        assertTrue(annotations.hasAny());
        assertNotNull(annotations.timeout());
        assertEquals(10000L, annotations.timeout().value());
    }

    @Test
    @DisplayName("Method-level annotations declared on parent interface are inherited")
    void methodLevelAnnotationOnParentInterface_isResolvedFromChild() throws Exception {
        Method method = ChildOfParentWithMethodLevel.class.getMethod("methodOnParent");
        ResilienceAnnotations annotations = ResilienceAnnotations.resolve(ChildOfParentWithMethodLevel.class, method);

        assertTrue(annotations.hasAny());
        assertNotNull(annotations.retry());
        assertEquals(9, annotations.retry().maxRetries());
        assertNotNull(annotations.timeout());
        assertEquals(8000L, annotations.timeout().value());
    }

    @Test
    @DisplayName("Class-level @Timeout on the child wins over the parent's class-level @Timeout")
    void childClassLevelTimeout_winsOverParentClassLevelTimeout() throws Exception {
        Method method = ChildOverridingClassLevelTimeout.class.getMethod("inheritedFromParent");
        ResilienceAnnotations annotations =
                ResilienceAnnotations.resolve(ChildOverridingClassLevelTimeout.class, method);

        assertNotNull(annotations.timeout());
        assertEquals(20000L, annotations.timeout().value());
        assertNotNull(annotations.circuitBreaker());
        assertEquals(7, annotations.circuitBreaker().maxFailures());
    }
}
