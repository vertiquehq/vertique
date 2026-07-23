// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link JsonProfile} annotation meta-annotations: runtime retention so the
 * annotation is reflectively readable at router-build, and {@code TYPE}/{@code METHOD} targets so it
 * may be placed on a JAX-RS resource class or method (FR-JSON-042).
 */
class JsonProfileAnnotationTest {

    @Test
    @DisplayName("@JsonProfile is retained at RUNTIME so it is reflectively readable")
    void retention_isRuntime() {
        Retention retention = JsonProfile.class.getAnnotation(Retention.class);
        assertNotNull(retention, "@JsonProfile must declare @Retention");
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    @DisplayName("@JsonProfile targets exactly TYPE and METHOD")
    void target_isTypeAndMethod() {
        Target target = JsonProfile.class.getAnnotation(Target.class);
        assertNotNull(target, "@JsonProfile must declare @Target");
        Set<ElementType> targets = Set.of(target.value());
        assertEquals(2, targets.size(), "@JsonProfile must target exactly two element types");
        assertTrue(targets.contains(ElementType.TYPE), "@JsonProfile must target TYPE");
        assertTrue(targets.contains(ElementType.METHOD), "@JsonProfile must target METHOD");
    }
}
