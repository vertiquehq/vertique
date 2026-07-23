// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RequiresAction} annotation.
 *
 * <p>Verifies that the annotation:
 * <ul>
 *   <li>is readable at runtime on a method ({@code METHOD} target)</li>
 *   <li>is readable at runtime on a type ({@code TYPE} target)</li>
 *   <li>has {@link RetentionPolicy#RUNTIME} retention</li>
 *   <li>declares both {@link ElementType#METHOD} and {@link ElementType#TYPE} as its targets</li>
 * </ul>
 */
class RequiresActionTest {

    // --- test fixtures ---

    @RequiresAction("cms.content.read")
    static class AnnotatedClass {

        @RequiresAction("cms.content.read")
        void annotatedMethod() {}

        void unannotatedMethod() {}
    }

    // --- method target ---

    @Test
    @DisplayName("annotation placed on a method is readable at runtime via reflection")
    void annotationPresent_onMethod_readable() throws NoSuchMethodException {
        Method method = AnnotatedClass.class.getDeclaredMethod("annotatedMethod");

        RequiresAction annotation = method.getAnnotation(RequiresAction.class);

        assertNotNull(annotation, "@RequiresAction must be readable at runtime on a method");
        assertEquals("cms.content.read", annotation.value());
    }

    // --- type target ---

    @Test
    @DisplayName("annotation placed on a type is readable at runtime via reflection")
    void annotationPresent_onType_readable() {
        RequiresAction annotation = AnnotatedClass.class.getAnnotation(RequiresAction.class);

        assertNotNull(annotation, "@RequiresAction must be readable at runtime on a type");
        assertEquals("cms.content.read", annotation.value());
    }

    // --- meta-annotations ---

    @Test
    @DisplayName("retention policy is RUNTIME")
    void retention_runtime() {
        Retention retention = RequiresAction.class.getAnnotation(Retention.class);

        assertNotNull(retention, "@RequiresAction must carry @Retention");
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    @DisplayName("target includes METHOD and TYPE")
    void target_includesMethod_andType() {
        Target target = RequiresAction.class.getAnnotation(Target.class);

        assertNotNull(target, "@RequiresAction must carry @Target");
        ElementType[] values = target.value();
        boolean hasMethod = false;
        boolean hasType = false;
        for (ElementType et : values) {
            if (et == ElementType.METHOD) {
                hasMethod = true;
            }
            if (et == ElementType.TYPE) {
                hasType = true;
            }
        }
        assertTrue(hasMethod, "@Target must include METHOD");
        assertTrue(hasType, "@Target must include TYPE");
    }
}
