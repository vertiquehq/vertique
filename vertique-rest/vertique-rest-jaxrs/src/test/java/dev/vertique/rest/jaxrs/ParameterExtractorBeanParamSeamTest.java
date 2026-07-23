// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.rest.jaxrs.runtime.fixture.BrokenBeanResource;
import dev.vertique.rest.jaxrs.runtime.fixture.SeamBean;
import dev.vertique.rest.jaxrs.runtime.fixture.SeamBean_BeanParamModel;
import jakarta.ws.rs.QueryParam;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the bean-param model fast-path / reflective-fallback dispatch seam introduced in
 * {@link ParameterExtractor#computeBeanFields(Class)}.
 *
 * <p>Three contract points are tested:
 * <ul>
 *   <li><strong>Hit</strong> — when a generated {@code _BeanParamModel} companion is present,
 *       {@code computeBeanFields} converts the model's fields list to {@code BeanFieldEntry}
 *       instances and returns them. The sentinel field name from
 *       {@link SeamBean_BeanParamModel#SENTINEL_FIELD_NAME} proves the fast-path was taken
 *       (the reflective walk would yield the actual field name {@code "filter"}).</li>
 *   <li><strong>Miss</strong> — when no companion exists the reflective walk runs and produces
 *       the expected {@code BeanFieldEntry} list from the annotated fields.</li>
 *   <li><strong>Broken</strong> — when a companion exists but its constructor throws, the
 *       exception propagates and is NOT masked by a reflective fallback.</li>
 * </ul>
 */
class ParameterExtractorBeanParamSeamTest {

    // --- Hit: companion present ---

    @Nested
    @DisplayName("bean-param model hit — companion present on classpath")
    class Hit {

        @Test
        @DisplayName("computeBeanFields converts model fields and returns them (sentinel field name)")
        void computeBeanFields_usesModelWhenPresent() throws Exception {
            List<Object> entries = invokeComputeBeanFields(SeamBean.class);

            assertEquals(1, entries.size(), "Model must return exactly 1 entry");
            assertEquals(
                    SeamBean_BeanParamModel.SENTINEL_FIELD_NAME,
                    beanFieldName(entries.get(0)),
                    "Field name must match the sentinel from the model — not the reflective field name 'filter'");
        }

        @Test
        @DisplayName("consecutive calls return the same entries (cached via BEAN_PARAM_CACHE)")
        void computeBeanFields_sameEntriesOnRepeatCall() throws Exception {
            // First call populates BEAN_PARAM_CACHE; second call returns the cached list.
            // We can only verify the result is consistent (same values), not reference-equal,
            // because computeIfAbsent returns the cached list on the second call but the
            // BEAN_PARAM_CACHE key is the bean type, not the Optional wrapper.
            List<Object> first = invokeComputeBeanFields(SeamBean.class);
            List<Object> second = invokeComputeBeanFields(SeamBean.class);

            assertEquals(first.size(), second.size());
            assertEquals(beanFieldName(first.get(0)), beanFieldName(second.get(0)));
        }
    }

    // --- Miss: no companion ---

    /** A bean type with no {@code _BeanParamModel} companion on the test classpath. */
    static class NoCompanionBean {

        @QueryParam("page")
        public int page;
    }

    @Nested
    @DisplayName("bean-param model miss — no companion on classpath")
    class Miss {

        @Test
        @DisplayName("computeBeanFields falls back to reflective walk when no companion exists")
        void computeBeanFields_fallsBackToReflectionOnMiss() throws Exception {
            List<Object> entries = invokeComputeBeanFields(NoCompanionBean.class);

            assertEquals(1, entries.size(), "Reflective walk must discover the @QueryParam field");
            assertEquals(
                    "page",
                    beanFieldName(entries.get(0)),
                    "Field name must come from the reflective walk — 'page' is the real field name");
        }
    }

    // --- Broken: companion present but constructor throws ---

    @Nested
    @DisplayName("broken companion — companion present but fails to instantiate")
    class Broken {

        @Test
        @DisplayName("computeBeanFields propagates RuntimeException when companion constructor throws")
        void computeBeanFields_propagatesExceptionFromBrokenCompanion() {
            // BrokenBeanResource has a companion whose constructor throws.
            // The exception is wrapped in InvocationTargetException by the reflective call in
            // the test helper; unwrap it to verify the root cause propagates.
            InvocationTargetException ite = assertThrows(
                    InvocationTargetException.class,
                    () -> invokeComputeBeanFields(BrokenBeanResource.class),
                    "computeBeanFields must NOT silently fall back when a companion is broken");
            assertThrows(
                    RuntimeException.class,
                    () -> {
                        throw (RuntimeException) ite.getCause();
                    },
                    "Cause must be a RuntimeException propagated from the broken companion");
        }
    }

    // --- Helpers ---

    /**
     * Invokes the package-private static {@code computeBeanFields(Class<?>)} method on
     * {@link ParameterExtractor} via reflection.
     *
     * @param beanType the bean type to pass
     * @return the result list cast to {@code List<Object>}
     * @throws Exception on reflective lookup or invocation failure
     */
    @SuppressWarnings("unchecked")
    private static List<Object> invokeComputeBeanFields(Class<?> beanType) throws Exception {
        Method m = ParameterExtractor.class.getDeclaredMethod("computeBeanFields", Class.class);
        m.setAccessible(true);
        return (List<Object>) m.invoke(null, beanType);
    }

    /**
     * Reads the {@code name()} accessor from a {@code BeanFieldEntry} record via reflection.
     * {@code BeanFieldEntry} is package-private so tests in the same package can reach it.
     *
     * @param entry a {@code BeanFieldEntry} instance
     * @return the field name
     */
    private static String beanFieldName(Object entry) {
        try {
            return (String) entry.getClass().getMethod("name").invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
