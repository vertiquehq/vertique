// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReflectiveMethodMetadataAnnotationsTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Own {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Inherited {
        String value() default "";
    }

    interface Declaration {
        @Inherited("declaration")
        void operation();
    }

    interface Redeclaration extends Declaration {
        @Own
        @Override
        void operation();
    }

    @Test
    @DisplayName("without merged annotations a lookup sees only the method's own annotations")
    void ownAnnotationsOnly() throws Exception {
        Method method = Redeclaration.class.getMethod("operation");
        ReflectiveMethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of());

        assertTrue(metadata.hasAnnotation(Own.class));
        assertFalse(metadata.hasAnnotation(Inherited.class));
    }

    @Test
    @DisplayName("merged annotations replace the method's own for lookups, and the first of a type wins")
    void mergedAnnotations() throws Exception {
        Method own = Redeclaration.class.getMethod("operation");
        Method inherited = Declaration.class.getMethod("operation");
        List<Annotation> merged = List.of(
                own.getAnnotation(Own.class), inherited.getAnnotation(Inherited.class), own.getAnnotation(Own.class));
        ReflectiveMethodMetadata metadata = new ReflectiveMethodMetadata(own, List.of(), merged);

        assertTrue(metadata.hasAnnotation(Own.class));
        assertTrue(metadata.hasAnnotation(Inherited.class));
        assertEquals(
                "declaration",
                metadata.findAnnotation(Inherited.class).orElseThrow().value());
        assertFalse(metadata.findAnnotation(Deprecated.class).isPresent());
    }
}
