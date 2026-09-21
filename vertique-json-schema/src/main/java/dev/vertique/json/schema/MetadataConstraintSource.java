// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import java.lang.System.Logger.Level;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link ConstraintSource} driven by Bean Validation metadata ({@link
 * Validator#getConstraintsForClass}), consulted <em>in addition to</em> the floor — the schema
 * library's own Jakarta Validation module for a scoped field or getter, {@link WalkConstraintSource}
 * for a creator parameter, setter, or builder method — whenever a {@link Validator} is supplied to
 * the generator. It never runs in place of the floor; see {@link ResolvedConstraints}.
 *
 * <p><strong>Join.</strong> A field- or getter-backed property joins to a {@link PropertyDescriptor}
 * by the member's Java bean name, read from {@code builtClass}'s own {@link BeanDescriptor} — never
 * by the wire name, and never by walking the class hierarchy by hand: the metadata API already
 * aggregates inherited and interface constraints for the leaf class. A creator-parameter property
 * joins to a {@link ParameterDescriptor} by its declaring constructor and {@link
 * AnnotatedParameter#getIndex()}; a static-factory creator parameter joins to nothing here — that
 * shape is outside what Bean Validation exposes ({@link BeanDescriptor#getConstraintsForConstructor}
 * covers constructors only) — but {@link WalkConstraintSource} still renders its own annotations
 * directly as the floor, so the constraint is not lost (C3). A setter property is folded into the
 * same property-name join as a field or getter. A builder-method property joins the same way only
 * when {@link BuilderBorrowDetector} judges the join sound (a Lombok builder, or the exact Lombok
 * builder shape) — otherwise it contributes no supplement either, matching {@link
 * InputPropertyDescriber}'s own floor-side borrow: this class's own reflection over {@code
 * builtClass} would otherwise find and re-add a hand-written builder's borrowed constraint even after
 * the floor stopped rendering it, silently reintroducing the over-strict schema the owner ruling
 * removed whenever a {@code Validator} happens to be supplied. Where a property matches nothing, it
 * contributes no supplement.
 *
 * <p><strong>Group filter.</strong> Only a constraint whose {@link ConstraintDescriptor#getGroups()}
 * is empty or contains {@link Default} is rendered; {@code @Valid} cascades are never consulted here
 * (the generator already descends into nested types on its own). A constraint in a non-{@code
 * Default} group is therefore never proposed as a correction either, so the floor's own rendering of
 * it (the schema library's module does not filter by group) is left standing.
 *
 * <p><strong>Composition.</strong> A composed constraint's own annotation type is essentially never
 * one this class knows how to render (it is normally an application-defined marker), so its {@link
 * ConstraintDescriptor#getComposingConstraints()} leaves are rendered instead, recursively.
 *
 * <p><strong>Container elements.</strong> A {@code List}/array value's element constraints ({@link
 * PropertyDescriptor#getConstrainedContainerElementTypes()}, type-argument index 0) merge onto the
 * property's {@code items} subschema when that subschema is an inline object; a {@code Map} value's
 * element position is not described by the generator today, so it is left unchanged, matching the
 * pre-existing gap. Rendered as an addition: an item-level correction is not a shape #606 covers.
 *
 * <p><strong>Additions vs. corrections.</strong> A rendered keyword is a <em>correction</em> — merged
 * onto the schema unconditionally, replacing whatever the floor already wrote — exactly when it comes
 * from one of the four shapes vertiquehq/vertique-dev#606 named as wrong: {@code @Range}, {@code
 * @Length}, {@code @URL}, or a {@code @Pattern} carrying a flag the floor cannot embed. Every other
 * rendered keyword is an <em>addition</em> — merged only where the floor left that keyword unset — so
 * a constraint the floor already rendered correctly (the common case: {@code @Size}, {@code @Min},
 * {@code @Max}, {@code @NotNull}, ...) is never disturbed by this source running a second time over
 * the same member.
 */
final class MetadataConstraintSource implements ConstraintSource {

    // W3: every recognized constraint type is matched by its fully-qualified annotation class name,
    // never by annotation-type simple name alone — an application-defined constraint whose simple name
    // collides with one of these (e.g. its own "@Size" composing "@Pattern") must never be mistaken for
    // the real jakarta.validation/Hibernate Validator type merely because the name happens to match.
    private static final String C_NOT_NULL = "jakarta.validation.constraints.NotNull";
    private static final String C_NOT_EMPTY = "jakarta.validation.constraints.NotEmpty";
    private static final String C_NOT_BLANK = "jakarta.validation.constraints.NotBlank";
    private static final String C_SIZE = "jakarta.validation.constraints.Size";
    private static final String C_MIN = "jakarta.validation.constraints.Min";
    private static final String C_MAX = "jakarta.validation.constraints.Max";
    private static final String C_DECIMAL_MIN = "jakarta.validation.constraints.DecimalMin";
    private static final String C_DECIMAL_MAX = "jakarta.validation.constraints.DecimalMax";
    private static final String C_POSITIVE = "jakarta.validation.constraints.Positive";
    private static final String C_POSITIVE_OR_ZERO = "jakarta.validation.constraints.PositiveOrZero";
    private static final String C_NEGATIVE = "jakarta.validation.constraints.Negative";
    private static final String C_NEGATIVE_OR_ZERO = "jakarta.validation.constraints.NegativeOrZero";
    private static final String C_PATTERN = "jakarta.validation.constraints.Pattern";
    private static final String C_EMAIL = "jakarta.validation.constraints.Email";
    private static final String C_LENGTH = "org.hibernate.validator.constraints.Length";
    private static final String C_RANGE = "org.hibernate.validator.constraints.Range";
    private static final String C_URL = "org.hibernate.validator.constraints.URL";

    /**
     * The annotation types (by fully-qualified class name, W3) whose rendering is a <em>correction</em>
     * (vertiquehq/vertique-dev#606): the floor — the schema library's Jakarta Validation module for a
     * scoped member, or {@link WalkConstraintSource} for an unscoped one — either does not recognize
     * these at all ({@code @Range}, {@code @Length}, {@code @URL}, which {@link WalkConstraintSource}
     * never renders) or renders them incompletely ({@code @Pattern}'s flags, which neither the module
     * nor the walk embeds into the {@code pattern} keyword; a second {@code @Pattern} on the same
     * member, S5, which neither can express as more than one {@code pattern} keyword at all). Every
     * other recognized annotation type is rendered as an addition instead.
     */
    private static final Set<String> CORRECTING_ANNOTATION_TYPES = Set.of(C_RANGE, C_LENGTH, C_URL, C_PATTERN);

    /**
     * JDK {@code System.Logger} rather than SLF4J: this module's own architecture rule (FR-JSON-070)
     * freezes its compile dependencies to victools, Jackson, Jakarta Validation/Swagger annotations,
     * and {@code vertique-core}, with no logging facade among them.
     */
    private static final System.Logger LOG = System.getLogger(MetadataConstraintSource.class.getName());

    /** Embeddable Java regex modifier character per {@code jakarta.validation.constraints.Pattern.Flag}. */
    private static final Map<String, Character> EMBEDDABLE_PATTERN_FLAGS = Map.of(
            "UNIX_LINES", 'd',
            "CASE_INSENSITIVE", 'i',
            "COMMENTS", 'x',
            "MULTILINE", 'm',
            "DOTALL", 's',
            "UNICODE_CASE", 'u');

    private final Validator validator;

    MetadataConstraintSource(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public ResolvedConstraints forScopedMember(Class<?> builtClass, String javaName, ConstraintValueKind kind) {
        return renderProperty(builtClass, javaName, kind);
    }

    @Override
    public ResolvedConstraints forUnscopedMember(
            Class<?> builtClass, String javaName, ConstraintValueKind kind, AnnotatedMember jacksonMember) {
        if (jacksonMember instanceof AnnotatedParameter parameter) {
            return forParameter(builtClass, parameter, kind);
        }
        Member raw = jacksonMember == null ? null : jacksonMember.getMember();
        if (raw instanceof Method method
                && method.getDeclaringClass() != builtClass
                && !method.getDeclaringClass().isAssignableFrom(builtClass)) {
            // A builder method: the same built-type property-name join a field or getter uses, but
            // only when the borrow is sound — see the class Javadoc's "Join" section. An unsound
            // builder's property contributes no supplement, matching the floor's own refusal to
            // borrow, so a Validator supplied to the generator cannot silently reintroduce the
            // over-strict constraint the floor stopped publishing.
            Field field = declaredField(builtClass, javaName);
            if (field == null || !BuilderBorrowDetector.isSoundBorrow(method, builtClass, field)) {
                return ResolvedConstraints.NONE;
            }
        }
        // A setter, or a sound builder method: the same join a field or getter uses, on the built type.
        return renderProperty(builtClass, javaName, kind);
    }

    /** The built type's own declared field of the given Java bean name, walking its superclass chain. */
    private static Field declaredField(Class<?> builtClass, String javaName) {
        for (Class<?> current = builtClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()
                        && field.getName().equals(javaName)) {
                    return field;
                }
            }
        }
        return null;
    }

    private ResolvedConstraints renderProperty(Class<?> builtClass, String javaName, ConstraintValueKind kind) {
        PropertyDescriptor property =
                validator.getConstraintsForClass(builtClass).getConstraintsForProperty(javaName);
        if (property == null) {
            return ResolvedConstraints.NONE;
        }
        String label = builtClass.getSimpleName() + "." + javaName;
        Rendered own = render(property.getConstraintDescriptors(), kind, label);
        Map<String, Object> items = itemConstraints(property, kind, label);
        Map<String, Object> additions = mergeItems(own.additions, items);
        return new ResolvedConstraints(additions, own.corrections, own.required);
    }

    private ResolvedConstraints forParameter(
            Class<?> builtClass, AnnotatedParameter parameter, ConstraintValueKind kind) {
        java.lang.reflect.Member owner =
                parameter.getOwner() == null ? null : parameter.getOwner().getMember();
        if (!(owner instanceof Constructor<?> constructor)) {
            // A static-factory creator: Bean Validation exposes constrained constructors only. This
            // contributes no supplement, but the floor — WalkConstraintSource, reading the parameter's
            // own annotations directly — still renders its own constraint independently (C3).
            return ResolvedConstraints.NONE;
        }
        ConstructorDescriptor constructorDescriptor = validator
                .getConstraintsForClass(builtClass)
                .getConstraintsForConstructor(constructor.getParameterTypes());
        if (constructorDescriptor == null) {
            return ResolvedConstraints.NONE;
        }
        int index = parameter.getIndex();
        List<ParameterDescriptor> parameters = constructorDescriptor.getParameterDescriptors();
        if (index < 0 || index >= parameters.size()) {
            return ResolvedConstraints.NONE;
        }
        String label = builtClass.getSimpleName() + "(param " + index + ")";
        Rendered rendered = render(parameters.get(index).getConstraintDescriptors(), kind, label);
        return new ResolvedConstraints(rendered.additions, rendered.corrections, rendered.required);
    }

    /** Container-element (list/array item) constraints, merged only onto an inline {@code items} object. */
    private Map<String, Object> itemConstraints(PropertyDescriptor property, ConstraintValueKind kind, String label) {
        if (kind != ConstraintValueKind.ARRAY) {
            return Map.of();
        }
        for (ContainerElementTypeDescriptor element : property.getConstrainedContainerElementTypes()) {
            if (element.getTypeArgumentIndex() != 0) {
                continue;
            }
            Rendered rendered = render(element.getConstraintDescriptors(), ConstraintValueKind.OTHER, label + "[item]");
            // Item-level shapes are always additions: #606 names no item-level correction, and the
            // floor never describes a Map value position's or a List/array item's own constraints at
            // all, so there is nothing here for a correction to unconditionally replace.
            Map<String, Object> combined = rendered.additions;
            if (!rendered.corrections.isEmpty()) {
                combined = new LinkedHashMap<>(combined);
                combined.putAll(rendered.corrections);
            }
            if (!combined.isEmpty()) {
                return combined;
            }
        }
        return Map.of();
    }

    private static Map<String, Object> mergeItems(Map<String, Object> additions, Map<String, Object> items) {
        if (items.isEmpty()) {
            return additions;
        }
        Map<String, Object> merged = new LinkedHashMap<>(additions);
        merged.put("items", items);
        return merged;
    }

    /**
     * One property's, parameter's, or container element's group-filtered, composition-flattened,
     * addition/correction-split render.
     */
    private record Rendered(Map<String, Object> additions, Map<String, Object> corrections, boolean required) {}

    private static Rendered render(Set<ConstraintDescriptor<?>> descriptors, ConstraintValueKind kind, String label) {
        Map<String, Object> additions = new LinkedHashMap<>();
        Map<String, Object> corrections = new LinkedHashMap<>();
        List<String> patterns = new ArrayList<>();
        boolean[] required = {false};
        for (ConstraintDescriptor<?> descriptor : descriptors) {
            renderOne(descriptor, kind, label, additions, corrections, patterns, required);
        }
        if (patterns.size() == 1) {
            corrections.put("pattern", patterns.get(0));
        } else if (patterns.size() > 1) {
            // S5: two (or more) @Pattern constraints in the default group on one member — the schema's
            // "pattern" keyword can only ever hold one regular expression, so multiple render as an
            // allOf of single-pattern subschemas instead of the second silently overwriting the first.
            // Sorted for deterministic output: ConstraintDescriptor#getComposingConstraints() and the
            // constraint Set Bean Validation hands back for a repeated annotation carry no guaranteed
            // iteration order.
            patterns.sort(null);
            corrections.put("allOf", List.copyOf(patterns));
        }
        return new Rendered(additions, corrections, required[0]);
    }

    private static void renderOne(
            ConstraintDescriptor<?> descriptor,
            ConstraintValueKind kind,
            String label,
            Map<String, Object> additions,
            Map<String, Object> corrections,
            List<String> patterns,
            boolean[] required) {
        if (!appliesInDefaultGroup(descriptor)) {
            // Never proposed as a correction either: the floor does not filter by group, so its own
            // rendering of a non-Default-group constraint (if it renders one at all) stays standing.
            return;
        }
        if (!descriptor.getComposingConstraints().isEmpty()) {
            // A composed constraint's own leaves render too, recursively — Hibernate Validator's own
            // built-in @Range and @URL are themselves implemented by composition (@Range composes
            // @Min + @Max with the same bounds; confirmed by disassembly/instrumentation), so their
            // leaves must still render, on top of — never instead of — the composing annotation's own
            // case below. What actually guards against misreading an unrelated shape is fully-qualified
            // matching (W3), not skipping the switch: an application-defined constraint whose simple
            // name collides with a recognized type (an app "@Size" composing "@Pattern") has a
            // different fully-qualified class name than jakarta.validation/Hibernate Validator's own
            // annotation, so it can never match one of the cases below and falls to the default branch
            // regardless of whether it also composes other constraints.
            for (ConstraintDescriptor<?> leaf : descriptor.getComposingConstraints()) {
                renderOne(leaf, kind, label, additions, corrections, patterns, required);
            }
        }
        String fqcn = descriptor.getAnnotation().annotationType().getName();
        Map<String, Object> attributes = descriptor.getAttributes();
        boolean array = kind == ConstraintValueKind.ARRAY;
        boolean map = kind == ConstraintValueKind.MAP;
        // #606: the floor either cannot recognize this annotation at all, or renders it incompletely,
        // so its keyword replaces the floor's unconditionally; every other keyword below only fills a
        // gap the floor left.
        Map<String, Object> keywords = CORRECTING_ANNOTATION_TYPES.contains(fqcn) ? corrections : additions;
        switch (fqcn) {
            case C_NOT_NULL -> required[0] = true;
            case C_NOT_EMPTY, C_NOT_BLANK -> {
                // Matches victools' own isNullable() exactly (confirmed by disassembly): @NotNull,
                // @NotBlank, and @NotEmpty are treated identically — any one of the three is
                // sufficient for NOT_NULLABLE_FIELD_IS_REQUIRED to mark the property required.
                required[0] = true;
                String minKeyword = array ? "minItems" : map ? "minProperties" : "minLength";
                keywords.putIfAbsent(minKeyword, 1);
            }
            case C_SIZE -> {
                Integer min = (Integer) attributes.get("min");
                Integer max = (Integer) attributes.get("max");
                String maxKeyword = array ? "maxItems" : map ? "maxProperties" : "maxLength";
                String minKeyword = array ? "minItems" : map ? "minProperties" : "minLength";
                if (min != null && min != 0) {
                    keywords.put(minKeyword, min);
                }
                if (max != null && max != Integer.MAX_VALUE) {
                    keywords.put(maxKeyword, max);
                }
            }
            case C_MIN -> keywords.put("minimum", (Long) attributes.get("value"));
            case C_MAX -> keywords.put("maximum", (Long) attributes.get("value"));
            case C_DECIMAL_MIN -> {
                boolean inclusive = (Boolean) attributes.getOrDefault("inclusive", Boolean.TRUE);
                keywords.put(
                        inclusive ? "minimum" : "exclusiveMinimum", new BigDecimal((String) attributes.get("value")));
            }
            case C_DECIMAL_MAX -> {
                boolean inclusive = (Boolean) attributes.getOrDefault("inclusive", Boolean.TRUE);
                keywords.put(
                        inclusive ? "maximum" : "exclusiveMaximum", new BigDecimal((String) attributes.get("value")));
            }
            case C_POSITIVE -> keywords.put("exclusiveMinimum", BigDecimal.ZERO);
            case C_POSITIVE_OR_ZERO -> keywords.put("minimum", BigDecimal.ZERO);
            case C_NEGATIVE -> keywords.put("exclusiveMaximum", BigDecimal.ZERO);
            case C_NEGATIVE_OR_ZERO -> keywords.put("maximum", BigDecimal.ZERO);
            case C_PATTERN -> patterns.add(renderPattern(attributes, label));
            case C_EMAIL -> keywords.put("format", "email");
            case C_LENGTH -> {
                Long min = asLong(attributes.get("min"));
                Long max = asLong(attributes.get("max"));
                if (min != null && min != 0) {
                    keywords.put("minLength", min);
                }
                if (max != null && max != Integer.MAX_VALUE) {
                    keywords.put("maxLength", max);
                }
            }
            case C_RANGE -> {
                Long min = asLong(attributes.get("min"));
                Long max = asLong(attributes.get("max"));
                if (min != null) {
                    keywords.put("minimum", BigDecimal.valueOf(min));
                }
                if (max != null) {
                    keywords.put("maximum", BigDecimal.valueOf(max));
                }
            }
            case C_URL -> keywords.put("format", "uri");
            default ->
                LOG.log(
                        Level.DEBUG,
                        "Bean Validation constraint @{0} on {1} has no JSON Schema rendering; skipped",
                        descriptor.getAnnotation().annotationType().getSimpleName(),
                        label);
        }
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * Renders a {@code @Pattern}'s regexp, embedding its flags as an inline Java regex modifier group
     * ({@code io.vertx.json.schema} 5.1.6 compiles the {@code pattern} keyword with
     * {@code java.util.regex.Pattern} and honors an embedded modifier group — measured in
     * {@code PatternFlagRenderingTest}). A flag with no embeddable modifier character
     * ({@code CANON_EQ}) cannot be expressed this way and fails generation with a bounded diagnostic.
     */
    private static String renderPattern(Map<String, Object> attributes, String label) {
        String regexp = (String) attributes.get("regexp");
        Object[] flags = (Object[]) attributes.get("flags");
        if (flags == null || flags.length == 0) {
            return regexp;
        }
        StringBuilder modifiers = new StringBuilder();
        for (Object flag : flags) {
            String name = flag.toString();
            Character embedded = EMBEDDABLE_PATTERN_FLAGS.get(name);
            if (embedded == null) {
                throw Diagnostics.failure(
                        "the @Pattern constraint on " + label + " declares flag " + name
                                + ", which has no embeddable java.util.regex modifier and cannot be expressed as a"
                                + " JSON Schema pattern; remove the flag or embed its effect in the regular"
                                + " expression itself",
                        null);
            }
            modifiers.append(embedded);
        }
        return "(?" + modifiers + ":" + regexp + ")";
    }

    private static boolean appliesInDefaultGroup(ConstraintDescriptor<?> descriptor) {
        Set<Class<?>> groups = descriptor.getGroups();
        return groups.isEmpty() || groups.contains(Default.class);
    }
}
