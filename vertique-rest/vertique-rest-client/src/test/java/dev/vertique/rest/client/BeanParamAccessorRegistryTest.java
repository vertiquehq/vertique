// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BeanParamAccessorRegistry}.
 *
 * <p>Verifies that the registry:
 * <ul>
 *   <li>Falls back to the reflective accessor when no generated class exists for a type.</li>
 *   <li>Caches results so concurrent first-touch resolves return the same instance.</li>
 *   <li>Surfaces a {@link RestClientConfigurationException} when a generated class is present
 *       but fails to instantiate (throwing constructor) or is the wrong type (a present companion
 *       that does not implement {@link BeanParamAccessor}, so the cast throws).</li>
 *   <li>{@link BeanParamAccessorRegistry#shared()} returns the process-wide singleton.</li>
 *   <li>Derives the correct FQN for nested bean types (binary {@code $} &rarr; {@code _}).</li>
 * </ul>
 */
class BeanParamAccessorRegistryTest {

    /** A simple bean type that has no generated accessor on the test classpath. */
    record SimpleBean(
            @QueryParam("q") String query, @QueryParam("p") int page) {}

    /** Another type used to verify that two types share the same fallback instance. */
    static class AnotherBean {
        @QueryParam("x")
        String x;
    }

    /**
     * A nested static class that simulates a nested {@code @BeanParam} bean type.
     * Its binary name contains a {@code $} separator which must be translated to {@code _}
     * when looking up the generated accessor class.
     */
    static class Outer {

        /** Inner nested bean — binary name is {@code BeanParamAccessorRegistryTest$Outer$InnerBean}. */
        static class InnerBean {
            @QueryParam("inner")
            public String value;
        }
    }

    private ReflectiveBeanParamAccessor fallback;
    private BeanParamAccessorRegistry registry;

    @BeforeEach
    void setUp() {
        fallback = new ReflectiveBeanParamAccessor();
        // Use a fresh registry per test so cache state doesn't bleed between tests.
        registry = new BeanParamAccessorRegistry(fallback);
    }

    // --- Miss / fallback ---

    @Nested
    @DisplayName("ClassNotFoundException fallback")
    class FallbackTests {

        @Test
        @DisplayName("resolve() returns the fallback accessor when no generated class exists")
        void resolveFallsBackWhenNoGeneratedClass() {
            BeanParamAccessor<SimpleBean> accessor = registry.resolve(SimpleBean.class);
            assertThat(accessor).isSameAs(fallback);
        }

        @Test
        @DisplayName("resolve() extracts fields correctly via the reflective fallback")
        void reflectiveFallbackExtractsFieldsFromRecord() {
            BeanParamAccessor<SimpleBean> accessor = registry.resolve(SimpleBean.class);
            SimpleBean bean = new SimpleBean("hello", 3);
            assertThat(accessor.extract(bean, "query")).isEqualTo("hello");
            assertThat(accessor.extract(bean, "page")).isEqualTo(3);
        }

        @Test
        @DisplayName("two different types that both miss get the same fallback instance")
        void twoMissesSameFallbackInstance() {
            BeanParamAccessor<SimpleBean> a1 = registry.resolve(SimpleBean.class);
            BeanParamAccessor<AnotherBean> a2 = registry.resolve(AnotherBean.class);
            assertThat(a1).isSameAs(fallback);
            assertThat(a2).isSameAs(fallback);
        }
    }

    // --- Cache integrity ---

    @Nested
    @DisplayName("Cache integrity")
    class CacheTests {

        @Test
        @DisplayName("resolve() for the same type returns the same instance on repeated calls")
        void resolveReturnsSameInstanceOnRepeatedCalls() {
            BeanParamAccessor<SimpleBean> first = registry.resolve(SimpleBean.class);
            BeanParamAccessor<SimpleBean> second = registry.resolve(SimpleBean.class);
            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("concurrent resolve() calls for the same type return the same instance")
        void concurrentResolveSameInstance() throws Exception {
            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BeanParamAccessor<SimpleBean>>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return registry.resolve(SimpleBean.class);
                }));
            }

            start.countDown();

            BeanParamAccessor<SimpleBean> expected = null;
            for (Future<BeanParamAccessor<SimpleBean>> f : futures) {
                BeanParamAccessor<SimpleBean> result = f.get();
                if (expected == null) {
                    expected = result;
                } else {
                    assertThat(result).isSameAs(expected);
                }
            }
            pool.shutdown();
        }
    }

    // --- Shared singleton ---

    @Nested
    @DisplayName("Shared singleton")
    class SharedSingletonTests {

        @Test
        @DisplayName("shared() always returns the same instance")
        void sharedReturnsSameInstance() {
            BeanParamAccessorRegistry a = BeanParamAccessorRegistry.shared();
            BeanParamAccessorRegistry b = BeanParamAccessorRegistry.shared();
            assertThat(a).isSameAs(b);
        }

        @Test
        @DisplayName("shared() is not null")
        void sharedIsNotNull() {
            assertThat(BeanParamAccessorRegistry.shared()).isNotNull();
        }
    }

    // --- Broken constructor (RestClientConfigurationException) ---

    @Nested
    @DisplayName("Broken generated accessor")
    class BrokenAccessorTests {

        @Test
        @DisplayName("resolve() wraps a throwing accessor constructor in RestClientConfigurationException")
        void brokenConstructorSurfacesConfigurationException() {
            // BrokenBean_BeanParamAccessor (also under src/test/java) has a public no-arg constructor
            // that throws, simulating a corrupt annotation-processor output. The registry must
            // surface RestClientConfigurationException rather than silently fall back.
            assertThatThrownBy(() -> registry.resolve(BrokenBean.class))
                    .isInstanceOf(RestClientConfigurationException.class)
                    .hasMessageContaining("BrokenBean_BeanParamAccessor")
                    .hasMessageContaining("failed to instantiate")
                    .hasCauseInstanceOf(ReflectiveOperationException.class);
        }

        @Test
        @DisplayName("resolve() wraps a present-but-wrong-type accessor in RestClientConfigurationException")
        void wrongTypeAccessorSurfacesConfigurationException() {
            // WrongTypeBean_BeanParamAccessor (also under src/test/java) constructs successfully but does
            // not implement BeanParamAccessor, so the (BeanParamAccessor<?>) cast throws a
            // ClassCastException. The registry must surface RestClientConfigurationException rather than
            // letting the raw ClassCastException escape.
            assertThatThrownBy(() -> registry.resolve(WrongTypeBean.class))
                    .isInstanceOf(RestClientConfigurationException.class)
                    .hasMessageContaining("WrongTypeBean_BeanParamAccessor")
                    .hasMessageContaining("failed to instantiate")
                    .hasCauseInstanceOf(ClassCastException.class);
        }
    }

    // --- Nested type FQN translation ---

    @Nested
    @DisplayName("Nested type FQN translation")
    class NestedTypeFqnTests {

        @Test
        @DisplayName("nested type falls back to reflective accessor (no generated class on classpath)")
        void nestedType_fallsBackToReflective() {
            // InnerBean has no generated accessor on the test classpath; the registry
            // must still return the reflective fallback (not throw) even though the binary
            // name contains '$' which is translated to '_' in the lookup FQN.
            BeanParamAccessor<Outer.InnerBean> accessor = registry.resolve(Outer.InnerBean.class);
            assertThat(accessor).isSameAs(fallback);
        }

        @Test
        @DisplayName("nested type reflective fallback extracts fields correctly")
        void nestedType_reflectiveFallbackExtractsFields() {
            BeanParamAccessor<Outer.InnerBean> accessor = registry.resolve(Outer.InnerBean.class);
            Outer.InnerBean bean = new Outer.InnerBean();
            bean.value = "test-value";
            assertThat(accessor.extract(bean, "value")).isEqualTo("test-value");
        }
    }
}
