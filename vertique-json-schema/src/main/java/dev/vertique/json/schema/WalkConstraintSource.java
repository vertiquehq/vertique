// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
        boolean array = kind == ConstraintValueKind.ARRAY;
        boolean object = kind == ConstraintValueKind.MAP;
        Map<String, Object> keywords = new LinkedHashMap<>();

        Max max = jacksonMember.getAnnotation(Max.class);
        if (max != null) {
            keywords.put("maximum", max.value());
        }
        Min min = jacksonMember.getAnnotation(Min.class);
        if (min != null) {
            keywords.put("minimum", min.value());
        }
        DecimalMax decimalMax = jacksonMember.getAnnotation(DecimalMax.class);
        if (decimalMax != null) {
            keywords.put(decimalMax.inclusive() ? "maximum" : "exclusiveMaximum", new BigDecimal(decimalMax.value()));
        }
        DecimalMin decimalMin = jacksonMember.getAnnotation(DecimalMin.class);
        if (decimalMin != null) {
            keywords.put(decimalMin.inclusive() ? "minimum" : "exclusiveMinimum", new BigDecimal(decimalMin.value()));
        }
        Size size = jacksonMember.getAnnotation(Size.class);
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
        Pattern pattern = jacksonMember.getAnnotation(Pattern.class);
        if (pattern != null) {
            keywords.put("pattern", pattern.regexp());
        }
        NotBlank notBlank = jacksonMember.getAnnotation(NotBlank.class);
        NotEmpty notEmpty = jacksonMember.getAnnotation(NotEmpty.class);
        if (notBlank != null || notEmpty != null) {
            String minKeyword = array ? "minItems" : object ? "minProperties" : "minLength";
            keywords.putIfAbsent(minKeyword, 1);
        }
        boolean required = jacksonMember.getAnnotation(NotNull.class) != null || notBlank != null || notEmpty != null;
        // Nothing precedes the floor for an unscoped member, so every keyword it renders is an
        // "addition" in name only; there is nothing yet on the schema for it to be conditional against.
        return new ResolvedConstraints(keywords, Map.of(), required);
    }
}
