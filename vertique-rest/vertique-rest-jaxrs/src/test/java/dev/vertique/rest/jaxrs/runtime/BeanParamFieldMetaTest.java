// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta;
import dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BeanParamFieldMeta} — verifies record component accessors and
 * value-based equality.
 */
class BeanParamFieldMetaTest {

    @Test
    @DisplayName("accessors return the values supplied at construction")
    void accessorsReturnConstructorArgs() {
        ParamMeta meta = new ParamMeta("userId", ParamSource.PATH, String.class);
        BeanParamFieldMeta fieldMeta = new BeanParamFieldMeta("userId", meta);

        assertEquals("userId", fieldMeta.name());
        assertEquals(meta, fieldMeta.meta());
    }

    @Test
    @DisplayName("two instances with identical components are equal")
    void equalWhenSameComponents() {
        ParamMeta meta = new ParamMeta("page", ParamSource.QUERY, int.class);
        BeanParamFieldMeta a = new BeanParamFieldMeta("page", meta);
        BeanParamFieldMeta b = new BeanParamFieldMeta("page", meta);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("two instances with different names are not equal")
    void notEqualWhenNamesDiffer() {
        ParamMeta meta = new ParamMeta("x", ParamSource.HEADER, String.class);
        BeanParamFieldMeta a = new BeanParamFieldMeta("x", meta);
        BeanParamFieldMeta b = new BeanParamFieldMeta("y", meta);

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("two instances with different ParamMeta are not equal")
    void notEqualWhenMetaDiffer() {
        ParamMeta metaA = new ParamMeta("field", ParamSource.PATH, String.class);
        ParamMeta metaB = new ParamMeta("field", ParamSource.QUERY, String.class);
        BeanParamFieldMeta a = new BeanParamFieldMeta("field", metaA);
        BeanParamFieldMeta b = new BeanParamFieldMeta("field", metaB);

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("toString includes name and meta")
    void toStringIncludesComponents() {
        ParamMeta meta = new ParamMeta("sort", ParamSource.QUERY, String.class);
        BeanParamFieldMeta fieldMeta = new BeanParamFieldMeta("sort", meta);

        String str = fieldMeta.toString();
        // Records include all component names in toString by convention
        assertEquals(true, str.contains("sort"));
    }
}
