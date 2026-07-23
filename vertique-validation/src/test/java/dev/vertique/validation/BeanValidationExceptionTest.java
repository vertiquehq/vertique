// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.ViolationDetail;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeanValidationExceptionTest {

    @Test
    void shouldCarryViolations() {
        var violations = List.of(
                new ViolationDetail("name", "must not be blank", "required", null),
                new ViolationDetail("age", "must be positive", "min", null));

        var ex = new BeanValidationException("Validation failed", violations);

        assertEquals("Validation failed", ex.getMessage());
        assertEquals(2, ex.violations().size());
        assertEquals("name", ex.violations().get(0).path());
        assertEquals("required", ex.violations().get(0).type());
    }

    @Test
    void shouldDefensivelyCopyViolations() {
        var violations = new ArrayList<>(List.of(ViolationDetail.of("name", "required")));

        var ex = new BeanValidationException("fail", violations);
        violations.add(ViolationDetail.of("extra", "added"));

        assertEquals(1, ex.violations().size());
    }

    @Test
    void shouldHandleNullViolations() {
        var ex = new BeanValidationException("fail", null);

        assertTrue(ex.violations().isEmpty());
    }

    @Test
    void shouldReturnUnmodifiableList() {
        var ex = new BeanValidationException("fail", List.of(ViolationDetail.of("x", "y")));

        assertThrows(UnsupportedOperationException.class, () -> ex.violations().add(ViolationDetail.of("z", "w")));
    }

    @Test
    void shouldExtendValidationException() {
        var ex = new BeanValidationException("test", List.of());

        assertInstanceOf(ValidationException.class, ex);
    }

    @Test
    void shouldSupportCause() {
        var cause = new RuntimeException("root");
        var ex = new BeanValidationException("fail", List.of(), cause);

        assertSame(cause, ex.getCause());
    }
}
