// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.hibernate.validator.constraints.Range;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BuiltInViolationArgsInspector}, verifying that each supported
 * constraint annotation produces the expected semantic argument map and that unknown
 * annotations return {@code null}.
 */
class BuiltInViolationArgsInspectorTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();
    private final BuiltInViolationArgsInspector inspector = new BuiltInViolationArgsInspector();

    @Test
    void shouldExtractSizeArgs() {
        var violation = validator.validate(new SizedDto("x")).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals(3, args.get("min"));
        assertEquals(100, args.get("max"));
    }

    @Test
    void shouldExtractDecimalMinArgs() {
        var violation = validator.validate(new DecimalDto("-1")).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals("0", args.get("value"));
        assertEquals(true, args.get("inclusive"));
    }

    @Test
    void shouldExtractMinArgs() {
        var violation = validator.validate(new MinDto(0)).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals(5L, args.get("value"));
    }

    @Test
    void shouldExtractMaxArgs() {
        var violation = validator.validate(new MaxDto(200)).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals(100L, args.get("value"));
    }

    @Test
    void shouldExtractPositiveArgs() {
        var violation = validator.validate(new PositiveDto(-1)).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals(0, args.get("min"));
        assertEquals(false, args.get("inclusive"));
    }

    @Test
    void shouldExtractPatternArgs() {
        var violation = validator.validate(new PatternDto("abc")).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals("\\d+", args.get("regexp"));
    }

    @Test
    void shouldExtractRangeArgs() {
        var violation = validator.validate(new RangeDto(200)).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNotNull(args);
        assertEquals(1L, args.get("min"));
        assertEquals(99L, args.get("max"));
    }

    @Test
    void shouldReturnNullForNoArgsAnnotation() {
        var violation = validator.validate(new RequiredDto(null)).iterator().next();
        Map<String, Object> args = inspector.extract(violation);

        assertNull(args);
    }

    @Test
    void shouldSupportKnownAnnotations() {
        assertTrue(inspector.supports(Size.class));
        assertTrue(inspector.supports(DecimalMin.class));
        assertTrue(inspector.supports(Positive.class));
        assertTrue(inspector.supports(Range.class));
    }

    @Test
    void shouldNotSupportUnknownAnnotations() {
        assertFalse(inspector.supports(NotNull.class));
    }

    // --- Test DTOs ---

    record SizedDto(@Size(min = 3, max = 100) String value) {}

    record DecimalDto(@DecimalMin("0") String value) {}

    record MinDto(@Min(5) int value) {}

    record MaxDto(@Max(100) int value) {}

    record PositiveDto(@Positive int value) {}

    record PatternDto(@Pattern(regexp = "\\d+") String value) {}

    record RangeDto(@Range(min = 1, max = 99) long value) {}

    record RequiredDto(@NotNull String value) {}
}
