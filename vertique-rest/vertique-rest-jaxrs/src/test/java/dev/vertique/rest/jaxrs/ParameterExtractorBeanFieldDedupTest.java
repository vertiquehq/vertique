// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.QueryParam;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for closed issue #30: {@code ParameterExtractor.computeBeanFields(...)} did not
 * deduplicate hidden fields when walking the class hierarchy. With a {@code @BeanParam} type
 * declaring the same field name on both a subclass and its superclass, the previous behaviour
 * walked subclass-then-superclass and produced two entries; {@code extractBeanParam}'s
 * {@code LinkedHashMap}-keyed materialization then overwrote the subclass write with the
 * superclass write — yielding superclass-wins at runtime, in disagreement with compile-time
 * {@code JaxRsBeanScanner.pathParamNames(...)} which uses subclass-wins (the JLS-conformant
 * interpretation).
 *
 * <p>Fix: {@code computeBeanFields} now dedupes by field name during the subclass→superclass walk
 * so the subclass field shadows the superclass field. Both compile-time and runtime produce
 * subclass-wins.
 */
class ParameterExtractorBeanFieldDedupTest {

    static class ParentBean {
        @QueryParam("name-from-parent")
        String name;
    }

    static class ChildBean extends ParentBean {
        @QueryParam("name-from-child")
        String name;
    }

    @Test
    @DisplayName("computeBeanFields: subclass field hides superclass field of the same name")
    void subclassWinsOnHiddenField() throws Exception {
        // Reach the package-private static helper via reflection (no public hook today, but the
        // test lives in the same package so we can call it directly).
        Method computeBeanFields = ParameterExtractor.class.getDeclaredMethod("computeBeanFields", Class.class);
        computeBeanFields.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> entries = invokeAsList(computeBeanFields, ChildBean.class);

        assertEquals(
                1,
                entries.stream().filter(e -> beanFieldName(e).equals("name")).count(),
                "Subclass should hide superclass field of the same name; expected exactly one 'name' entry.");

        Object onlyEntry = entries.stream()
                .filter(e -> beanFieldName(e).equals("name"))
                .findFirst()
                .orElseThrow();
        ResourceMethodMeta.ParamMeta meta = beanFieldMeta(onlyEntry);
        assertEquals(
                "name-from-child",
                meta.name(),
                "Retained entry must be the subclass annotation (subclass-wins); got " + meta.name());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> invokeAsList(Method computeBeanFields, Class<?> beanType)
            throws IllegalAccessException, InvocationTargetException {
        return (List<Object>) computeBeanFields.invoke(null, beanType);
    }

    private static String beanFieldName(Object entry) {
        try {
            // BeanFieldEntry is a package-private record with a `name()` accessor.
            Method name = entry.getClass().getMethod("name");
            return (String) name.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static ResourceMethodMeta.ParamMeta beanFieldMeta(Object entry) {
        try {
            Method meta = entry.getClass().getMethod("meta");
            return (ResourceMethodMeta.ParamMeta) meta.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
