// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The floor {@link ConstraintSource}: the pre-existing hand translation from Jackson's merged
 * annotation map, unchanged in behavior from before this abstraction existed.
 *
 * <p>A field or getter with a schema-library member scope is left entirely to the library's own
 * Jackson and Jakarta Validation modules, which now run unconditionally — {@link #forScopedMember} is
 * therefore always {@link ResolvedConstraints#NONE}; nothing here would ever add to what the module
 * already wrote. A creator parameter, setter, or builder method has no such scope; its constraints are
 * read directly from {@code jacksonMember.getAnnotation(...)}, which already carries the same-named
 * field's and getter's merged annotations. {@link #INSTANCE} is called unconditionally as the floor
 * for that member kind, whether or not a {@link MetadataConstraintSource} supplement is also active.
 *
 * <p>The translated vocabulary — shared, byte-for-byte, between {@link #forUnscopedMember} and
 * {@link #forTypeUse} — is {@code @Max}/{@code @Min}/{@code @DecimalMax}/{@code @DecimalMin},
 * {@code @Positive}/{@code @PositiveOrZero}/{@code @Negative}/{@code @NegativeOrZero}, {@code @Size},
 * and {@code @Pattern}/{@code @NotBlank}/{@code @NotEmpty}; {@code @NotNull} contributes only to a
 * member's own {@code required}-ness. {@code @Email} and any Hibernate-only constraint (for example
 * {@code @Range}) are never translated by the floor.
 */
final class WalkConstraintSource implements ConstraintSource {

    static final WalkConstraintSource INSTANCE = new WalkConstraintSource();

    private WalkConstraintSource() {}

    @Override
    public ResolvedConstraints forScopedMember(Class<?> builtClass, String javaName, ConstraintValueKind kind) {
        return ResolvedConstraints.NONE;
    }

    @Override
    public ResolvedConstraints forUnscopedMember(
            Class<?> builtClass,
            String javaName,
            ConstraintValueKind kind,
            AnnotatedMember jacksonMember,
            BeanPropertyDefinition builtProperty) {
        // The walk reads jacksonMember's own merged annotation map directly; it needs no resolved
        // built-type property identity and does not consult builtProperty.
        if (jacksonMember == null) {
            return ResolvedConstraints.NONE;
        }
        Map<String, Object> keywords = translateWalkVocabulary(jacksonMember::getAnnotation, kind);
        boolean required = jacksonMember.getAnnotation(NotNull.class) != null
                || jacksonMember.getAnnotation(NotBlank.class) != null
                || jacksonMember.getAnnotation(NotEmpty.class) != null;
        // Nothing precedes the floor for an unscoped member, so every keyword it renders is an
        // "addition" in name only; there is nothing yet on the schema for it to be conditional against.
        return new ResolvedConstraints(keywords, Map.of(), required);
    }

    /**
     * Translates the walk vocabulary declared on a value position's own {@link AnnotatedType} — the same
     * annotation set {@link #forUnscopedMember} translates off a member's merged annotation map, read
     * through the very same {@link #translateWalkVocabulary} block both methods share — into a
     * {@link ResolvedConstraints}, for the type-use constraint overlay step rest-023 T003 ({@code D001})
     * activates ({@link ValuePositionRenderer}). A composed (meta-annotated) constraint resolves exactly
     * as {@link #forUnscopedMember} already resolves one: this method reads the same direct annotation
     * types off {@code annotatedType}, with no additional traversal of its own.
     *
     * <p>Every translated keyword is carried as an <em>addition</em> — there is nothing preceding this
     * step for a fresh value position to be conditional against, mirroring {@link #forUnscopedMember}'s
     * own reasoning. {@code required} is always {@code false}: required-ness has no meaning at a value
     * position, distinct from a member's own required-ness, which the floor tracks separately and this
     * method does not touch.
     *
     * @param annotatedType the position's own {@link AnnotatedType}, or {@code null} when the position
     *                      carries none
     * @param kind          the value shape the constrained position renders as (selects between the
     *                      string/array/map keyword families for a {@code @Size}-shaped constraint)
     * @return the resolved constraints; {@link ResolvedConstraints#NONE} when {@code annotatedType} is
     *     {@code null} or carries no recognized annotation
     */
    ResolvedConstraints forTypeUse(AnnotatedType annotatedType, ConstraintValueKind kind) {
        if (annotatedType == null) {
            return ResolvedConstraints.NONE;
        }
        Map<String, Object> keywords = translateWalkVocabulary(annotatedType::getAnnotation, kind);
        // NotNull carries no keyword here, unlike forUnscopedMember's own required computation: a value
        // position has no required-ness of its own for this constraint to express through (see this
        // method's own Javadoc).
        return new ResolvedConstraints(keywords, Map.of(), false);
    }

    /**
     * Reads one annotation type off an unscoped member or a value position's own {@link AnnotatedType} —
     * the two annotation carriers {@link #forUnscopedMember} and {@link #forTypeUse} each translate — so
     * {@link #translateWalkVocabulary} can read either through the same shared block.
     */
    @FunctionalInterface
    private interface AnnotationSource {
        <A extends Annotation> A get(Class<A> annotationType);
    }

    /**
     * Translates the walk vocabulary — {@code @Max}/{@code @Min}/{@code @DecimalMax}/{@code @DecimalMin},
     * {@code @Positive}/{@code @PositiveOrZero}/{@code @Negative}/{@code @NegativeOrZero}, {@code @Size},
     * and {@code @Pattern}/{@code @NotBlank}/{@code @NotEmpty} — off {@code source}, shared verbatim by
     * {@link #forUnscopedMember} and {@link #forTypeUse}. {@code @NotNull} is not read here: it never
     * contributes a keyword, only a caller's own {@code required} computation, which the two callers
     * derive differently (see each method's own Javadoc).
     */
    private static Map<String, Object> translateWalkVocabulary(AnnotationSource source, ConstraintValueKind kind) {
        boolean array = kind == ConstraintValueKind.ARRAY;
        boolean object = kind == ConstraintValueKind.MAP;
        Map<String, Object> keywords = new LinkedHashMap<>();

        Max max = source.get(Max.class);
        if (max != null) {
            keywords.put("maximum", max.value());
        }
        Min min = source.get(Min.class);
        if (min != null) {
            keywords.put("minimum", min.value());
        }
        DecimalMax decimalMax = source.get(DecimalMax.class);
        if (decimalMax != null) {
            keywords.put(decimalMax.inclusive() ? "maximum" : "exclusiveMaximum", new BigDecimal(decimalMax.value()));
        }
        DecimalMin decimalMin = source.get(DecimalMin.class);
        if (decimalMin != null) {
            keywords.put(decimalMin.inclusive() ? "minimum" : "exclusiveMinimum", new BigDecimal(decimalMin.value()));
        }
        if (source.get(Positive.class) != null) {
            mergeLowerBound(keywords, "exclusiveMinimum", BigDecimal.ZERO);
        }
        if (source.get(PositiveOrZero.class) != null) {
            mergeLowerBound(keywords, "minimum", BigDecimal.ZERO);
        }
        if (source.get(Negative.class) != null) {
            mergeUpperBound(keywords, "exclusiveMaximum", BigDecimal.ZERO);
        }
        if (source.get(NegativeOrZero.class) != null) {
            mergeUpperBound(keywords, "maximum", BigDecimal.ZERO);
        }
        Size size = source.get(Size.class);
        if (size != null) {
            String maxKeyword = array ? "maxItems" : object ? "maxProperties" : "maxLength";
            String minKeyword = array ? "minItems" : object ? "minProperties" : "minLength";
            if (size.max() != Integer.MAX_VALUE) {
                keywords.put(maxKeyword, size.max());
            }
            if (size.min() > 0) {
                keywords.put(minKeyword, size.min());
            }
        }
        Pattern pattern = source.get(Pattern.class);
        if (pattern != null) {
            keywords.put("pattern", pattern.regexp());
        }
        NotBlank notBlank = source.get(NotBlank.class);
        NotEmpty notEmpty = source.get(NotEmpty.class);
        if (notBlank != null || notEmpty != null) {
            String minKeyword = array ? "minItems" : object ? "minProperties" : "minLength";
            keywords.putIfAbsent(minKeyword, 1);
        }
        return keywords;
    }

    /**
     * Writes the {@code @Positive}/{@code @PositiveOrZero}/{@code @Negative}/{@code @NegativeOrZero}
     * family's own lower-bound keyword ({@code exclusiveMinimum} or {@code minimum}) without loosening a
     * stricter {@code @DecimalMin} (or {@code @Min}) already translated onto the same keyword: a larger
     * lower bound is the stricter one, so it wins; the losing side's original value object is left
     * untouched (never replaced with an equal-valued copy), and an absent keyword is always filled in.
     */
    private static void mergeLowerBound(Map<String, Object> keywords, String keyword, BigDecimal candidate) {
        Object existing = keywords.get(keyword);
        if (existing != null && existingAsBigDecimal(existing).compareTo(candidate) >= 0) {
            return;
        }
        keywords.put(keyword, candidate);
    }

    /**
     * Writes the family's own upper-bound keyword ({@code exclusiveMaximum} or {@code maximum}) without
     * loosening a stricter {@code @DecimalMax} (or {@code @Max}) already translated onto the same keyword:
     * a smaller upper bound is the stricter one, so it wins; see {@link #mergeLowerBound} for the mirrored
     * lower-bound rule this method shares its reasoning with.
     */
    private static void mergeUpperBound(Map<String, Object> keywords, String keyword, BigDecimal candidate) {
        Object existing = keywords.get(keyword);
        if (existing != null && existingAsBigDecimal(existing).compareTo(candidate) <= 0) {
            return;
        }
        keywords.put(keyword, candidate);
    }

    /**
     * Reads an already-translated bound keyword's value back as a {@link BigDecimal} for comparison only —
     * {@link #mergeLowerBound} and {@link #mergeUpperBound} never write this converted value back onto the
     * map, so an untouched {@code @DecimalMin}/{@code @DecimalMax} keeps rendering its own exact
     * {@link BigDecimal}, and an untouched {@code @Min}/{@code @Max} keeps rendering its own exact
     * {@link Long}. Every value this class itself ever writes to a bound keyword is one of those two types.
     */
    private static BigDecimal existingAsBigDecimal(Object existing) {
        return existing instanceof Long asLong ? BigDecimal.valueOf(asLong) : (BigDecimal) existing;
    }
}
