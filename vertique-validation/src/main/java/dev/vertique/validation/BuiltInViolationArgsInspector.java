// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.hibernate.validator.constraints.CodePointLength;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.Range;

/**
 * Built-in {@link ViolationArgsInspector} with specific extractors for standard Jakarta
 * and Hibernate Validator constraints.
 *
 * <p>Each supported constraint type has a dedicated extractor that returns well-defined,
 * semantic arguments. No reflection is used — extractors directly cast annotations and
 * read their attributes.
 *
 * <p>Returns {@code null} for unknown annotations. Custom annotations should provide
 * their own {@link ViolationArgsInspector} via Dagger multibinding.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code @Size(min=1, max=100)} &rarr; {@code {min: 1, max: 100}}</li>
 *   <li>{@code @DecimalMin("5")} &rarr; {@code {value: "5", inclusive: true}}</li>
 *   <li>{@code @Positive} &rarr; {@code {min: 0, inclusive: false}}</li>
 *   <li>{@code @NotNull} &rarr; {@code null} (no meaningful args)</li>
 * </ul>
 */
class BuiltInViolationArgsInspector implements ViolationArgsInspector {

    private static final Map<Class<? extends Annotation>, Function<Annotation, Map<String, Object>>> EXTRACTORS =
            Map.ofEntries(
                    // Jakarta constraints
                    entry(Size.class, a -> {
                        var s = (Size) a;
                        return Map.of("min", s.min(), "max", s.max());
                    }),
                    entry(DecimalMin.class, a -> {
                        var d = (DecimalMin) a;
                        return Map.of("value", d.value(), "inclusive", d.inclusive());
                    }),
                    entry(DecimalMax.class, a -> {
                        var d = (DecimalMax) a;
                        return Map.of("value", d.value(), "inclusive", d.inclusive());
                    }),
                    entry(Min.class, a -> Map.of("value", ((Min) a).value())),
                    entry(Max.class, a -> Map.of("value", ((Max) a).value())),
                    entry(jakarta.validation.constraints.Positive.class, a -> Map.of("min", 0, "inclusive", false)),
                    entry(
                            jakarta.validation.constraints.PositiveOrZero.class,
                            a -> Map.of("min", 0, "inclusive", true)),
                    entry(jakarta.validation.constraints.Negative.class, a -> Map.of("max", 0, "inclusive", false)),
                    entry(
                            jakarta.validation.constraints.NegativeOrZero.class,
                            a -> Map.of("max", 0, "inclusive", true)),
                    entry(Digits.class, a -> {
                        var d = (Digits) a;
                        return Map.of("integer", d.integer(), "fraction", d.fraction());
                    }),
                    entry(Pattern.class, a -> Map.of("regexp", ((Pattern) a).regexp())),
                    entry(Email.class, a -> {
                        String regexp = ((Email) a).regexp();
                        return regexp.equals(".*") ? null : Map.of("regexp", regexp);
                    }),
                    // Hibernate constraints
                    entry(Length.class, a -> {
                        var l = (Length) a;
                        return Map.of("min", l.min(), "max", l.max());
                    }),
                    entry(CodePointLength.class, a -> {
                        var c = (CodePointLength) a;
                        var args = new LinkedHashMap<String, Object>();
                        args.put("min", c.min());
                        args.put("max", c.max());
                        if (c.normalizationStrategy() != CodePointLength.NormalizationStrategy.NONE) {
                            args.put(
                                    "normalizationStrategy",
                                    c.normalizationStrategy().name());
                        }
                        return Map.copyOf(args);
                    }),
                    entry(Range.class, a -> {
                        var r = (Range) a;
                        return Map.of("min", r.min(), "max", r.max());
                    }));

    /**
     * Creates a typed map entry for the extractors map, pairing an annotation class with
     * its argument extractor function.
     *
     * @param <A>       the annotation type
     * @param type      the annotation class
     * @param extractor the function that extracts args from an annotation instance
     * @return a map entry for use in {@link Map#ofEntries}
     */
    private static <A extends Annotation>
            Map.Entry<Class<? extends Annotation>, Function<Annotation, Map<String, Object>>> entry(
                    Class<A> type, Function<Annotation, Map<String, Object>> extractor) {
        return Map.entry(type, extractor);
    }

    /**
     * Returns {@code true} if the built-in extractors include the given constraint annotation.
     *
     * @param constraintAnnotation the constraint annotation class
     * @return {@code true} if this inspector has a built-in extractor for the annotation
     */
    @Override
    public boolean supports(Class<? extends Annotation> constraintAnnotation) {
        return EXTRACTORS.containsKey(constraintAnnotation);
    }

    /**
     * Extracts constraint arguments using the built-in extractor for the given annotation type.
     *
     * @param violation the constraint violation
     * @return a map of argument names to values, or {@code null} if no extractor is registered
     *         for the annotation type (including annotations with no meaningful args)
     */
    @Override
    public Map<String, Object> extract(ConstraintViolation<?> violation) {
        Annotation annotation = violation.getConstraintDescriptor().getAnnotation();
        Function<Annotation, Map<String, Object>> extractor = EXTRACTORS.get(annotation.annotationType());
        if (extractor == null) {
            return null;
        }
        return extractor.apply(annotation);
    }
}
