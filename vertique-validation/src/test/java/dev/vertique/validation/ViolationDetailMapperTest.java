// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViolationDetailMapperTest {

    private static final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldResolveMappedType() {
        var mapper = new ViolationDetailMapper(
                Set.of(new ViolationTypeMapping(NotNull.class, "required")), Set.of(), Set.of());

        var violations = validator.validate(new RequiredDto(null));
        var details = mapper.toDetails(violations);

        assertEquals(1, details.size());
        assertEquals("required", details.get(0).type());
    }

    @Test
    void contributionForBuiltInAnnotationWinsWhenOrderedBeforeTheBuiltIn() {
        var contribution = new ViolationTypeMapping(NotNull.class, "custom");
        var builtIn = new ViolationTypeMapping(NotNull.class, "required");
        var mappings = new LinkedHashSet<ViolationTypeMapping>();
        mappings.add(contribution);
        mappings.add(builtIn);
        var mapper = new ViolationDetailMapper(mappings, Set.of(), Set.of());

        var details = mapper.toDetails(validator.validate(new RequiredDto(null)));

        assertEquals("custom", details.get(0).type());
    }

    @Test
    void twoContributionsForOneAnnotationFailStartup() {
        var mappings = new LinkedHashSet<ViolationTypeMapping>();
        mappings.add(new ViolationTypeMapping(Size.class, "first"));
        mappings.add(new ViolationTypeMapping(Size.class, "second"));

        var failure = assertThrows(
                ConfigurationException.class, () -> new ViolationDetailMapper(mappings, Set.of(), Set.of()));

        assertTrue(failure.getMessage().contains(Size.class.getName()));
    }

    @Test
    void twoContributionsOverridingOneBuiltInFailStartupWhateverTheOrder() {
        var builtIn = new ViolationTypeMapping(NotNull.class, "required");
        var first = new ViolationTypeMapping(NotNull.class, "first");
        var second = new ViolationTypeMapping(NotNull.class, "second");
        var builtInFirst = new LinkedHashSet<ViolationTypeMapping>(java.util.List.of(builtIn, first, second));
        var builtInLast = new LinkedHashSet<ViolationTypeMapping>(java.util.List.of(first, second, builtIn));

        assertThrows(ConfigurationException.class, () -> new ViolationDetailMapper(builtInFirst, Set.of(), Set.of()));
        assertThrows(ConfigurationException.class, () -> new ViolationDetailMapper(builtInLast, Set.of(), Set.of()));
    }

    @Test
    void identicalContributionsForOneAnnotationAreOneBinding() {
        var mappings = new LinkedHashSet<ViolationTypeMapping>();
        mappings.add(new ViolationTypeMapping(Size.class, "same"));
        mappings.add(new ViolationTypeMapping(Size.class, "same"));
        var mapper = new ViolationDetailMapper(mappings, Set.of(), Set.of());

        assertEquals(
                "same",
                mapper.toDetails(validator.validate(new SizedDto("x"))).get(0).type());
    }

    @Test
    void shouldResolveProgrammaticMapperType() {
        ViolationTypeMapper programmatic = annotation -> {
            if (annotation == Size.class) return "custom_size";
            return null;
        };
        var mapper = new ViolationDetailMapper(Set.of(), Set.of(programmatic), Set.of());

        var violations = validator.validate(new SizedDto("x"));
        var details = mapper.toDetails(violations);

        assertEquals(1, details.size());
        assertEquals("custom_size", details.get(0).type());
    }

    @Test
    void shouldFallBackToAnnotationSimpleName() {
        var mapper = new ViolationDetailMapper(Set.of(), Set.of(), Set.of());

        var violations = validator.validate(new RequiredDto(null));
        var details = mapper.toDetails(violations);

        assertEquals(1, details.size());
        assertEquals("NotNull", details.get(0).type());
    }

    @Test
    void shouldExtractDefaultArgs() {
        var mapper =
                new ViolationDetailMapper(Set.of(new ViolationTypeMapping(Size.class, "size")), Set.of(), Set.of());

        var violations = validator.validate(new SizedDto("x"));
        var details = mapper.toDetails(violations);

        assertEquals(1, details.size());
        assertNotNull(details.get(0).args());
        assertEquals(3, details.get(0).args().get("min"));
        assertEquals(10, details.get(0).args().get("max"));
    }

    @Test
    void shouldReturnNullArgsForNoMeaningfulAttributes() {
        var mapper = new ViolationDetailMapper(Set.of(), Set.of(), Set.of());

        var violations = validator.validate(new RequiredDto(null));
        var details = mapper.toDetails(violations);

        assertEquals(1, details.size());
        assertNull(details.get(0).args());
    }

    @Test
    void shouldUseCustomArgsInspector() {
        ViolationArgsInspector customInspector = new ViolationArgsInspector() {
            @Override
            public boolean supports(Class<? extends java.lang.annotation.Annotation> constraintAnnotation) {
                return constraintAnnotation == Size.class;
            }

            @Override
            public Map<String, Object> extract(jakarta.validation.ConstraintViolation<?> violation) {
                return Map.of("custom", "value");
            }
        };
        var mapper = new ViolationDetailMapper(Set.of(), Set.of(), Set.of(customInspector));

        var violations = validator.validate(new SizedDto("x"));
        var details = mapper.toDetails(violations);

        assertEquals(Map.of("custom", "value"), details.get(0).args());
    }

    @Test
    void shouldPreferMappingsOverMappers() {
        ViolationTypeMapper programmatic = annotation -> "from_mapper";
        var mapper = new ViolationDetailMapper(
                Set.of(new ViolationTypeMapping(NotNull.class, "from_mapping")), Set.of(programmatic), Set.of());

        var violations = validator.validate(new RequiredDto(null));
        var details = mapper.toDetails(violations);

        assertEquals("from_mapping", details.get(0).type());
    }

    // --- Test DTOs ---

    record RequiredDto(@NotNull String value) {}

    record SizedDto(@Size(min = 3, max = 10) String value) {}
}
