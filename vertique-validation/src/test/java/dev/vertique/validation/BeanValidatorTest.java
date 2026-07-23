// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.BeanValidator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BeanValidatorTest {

    private static BeanValidator beanValidator;

    @BeforeAll
    static void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ViolationDetailMapper mapper = new ViolationDetailMapper(
                Set.of(
                        new ViolationTypeMapping(NotBlank.class, "required"),
                        new ViolationTypeMapping(Positive.class, "min"),
                        new ViolationTypeMapping(Size.class, "size")),
                Set.of(),
                Set.of());
        beanValidator = new DefaultBeanValidator(validator, mapper);
    }

    @Test
    void shouldPassValidObject() {
        var dto = new TestDto("Alice", 25);

        assertDoesNotThrow(() -> beanValidator.validate(dto));
    }

    @Test
    void shouldThrowForInvalidObject() {
        var dto = new TestDto("", -1);

        var ex = assertThrows(BeanValidationException.class, () -> beanValidator.validate(dto));

        assertEquals(2, ex.violations().size());
    }

    @Test
    void shouldIncludeViolationDetailsWithType() {
        var dto = new TestDto("", 5);

        var ex = assertThrows(BeanValidationException.class, () -> beanValidator.validate(dto));

        assertEquals(1, ex.violations().size());
        var detail = ex.violations().get(0);
        assertEquals("name", detail.path());
        assertNotNull(detail.message());
        assertEquals("required", detail.type());
    }

    @Test
    void shouldIncludeConstraintArgs() {
        var dto = new SizedDto("ab");

        var ex = assertThrows(BeanValidationException.class, () -> beanValidator.validate(dto));

        assertEquals(1, ex.violations().size());
        var detail = ex.violations().get(0);
        assertEquals("size", detail.type());
        assertNotNull(detail.args());
        assertEquals(3, detail.args().get("min"));
        assertEquals(100, detail.args().get("max"));
    }

    @Test
    void shouldValidateWithGroups() {
        var dto = new GroupedDto(null, "value");

        assertDoesNotThrow(() -> beanValidator.validate(dto));

        var ex = assertThrows(BeanValidationException.class, () -> beanValidator.validate(dto, Create.class));

        assertEquals(1, ex.violations().size());
        assertEquals("name", ex.violations().get(0).path());
        assertEquals("required", ex.violations().get(0).type());
    }

    @Test
    void shouldCheckReturnViolationDetails() {
        var dto = new TestDto("", -1);

        var violations = beanValidator.check(dto);

        assertEquals(2, violations.size());
        assertTrue(violations.stream().allMatch(v -> v.type() != null));
    }

    @Test
    void shouldCheckReturnEmptyListForValidObject() {
        var dto = new TestDto("Alice", 25);

        var violations = beanValidator.check(dto);

        assertTrue(violations.isEmpty());
    }

    @Test
    void shouldCheckWithGroupsReturnViolationDetails() {
        var dto = new GroupedDto(null, "value");

        var defaultViolations = beanValidator.check(dto, jakarta.validation.groups.Default.class);
        assertTrue(defaultViolations.isEmpty());

        var createViolations = beanValidator.check(dto, Create.class);
        assertEquals(1, createViolations.size());
        assertEquals("required", createViolations.get(0).type());
    }

    // --- Test DTOs ---

    record TestDto(@NotBlank String name, @Positive int age) {}

    record SizedDto(@Size(min = 3, max = 100) String value) {}

    interface Create {}

    record GroupedDto(@NotBlank(groups = Create.class) String name, String value) {}
}
