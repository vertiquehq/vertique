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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link ConstraintSource} driven by Bean Validation metadata ({@link
 * Validator#getConstraintsForClass}), used whenever a {@link Validator} is supplied to the generator.
 *
 * <p><strong>Join.</strong> A field- or getter-backed property joins to a {@link PropertyDescriptor}
 * by the member's Java bean name, read from {@code builtClass}'s own {@link BeanDescriptor} — never
 * by the wire name, and never by walking the class hierarchy by hand: the metadata API already
 * aggregates inherited and interface constraints for the leaf class. A creator-parameter property
 * joins to a {@link ParameterDescriptor} by its declaring constructor and {@link
 * AnnotatedParameter#getIndex()}; a static-factory creator parameter joins to nothing; that shape is
 * outside what Bean Validation exposes ({@link BeanDescriptor#getConstraintsForConstructor} covers
 * constructors only). A setter or builder-method property is folded into the same property-name join
 * as a field or getter, matching the pre-existing borrow. Where a property matches nothing, it gets no
 * constraints from the metadata.
 *
 * <p><strong>Group filter.</strong> Only a constraint whose {@link ConstraintDescriptor#getGroups()}
 * is empty or contains {@link Default} is rendered; {@code @Valid} cascades are never consulted here
 * (the generator already descends into nested types on its own).
 *
 * <p><strong>Composition.</strong> A composed constraint's own annotation type is essentially never
 * one this class knows how to render (it is normally an application-defined marker), so its {@link
 * ConstraintDescriptor#getComposingConstraints()} leaves are rendered instead, recursively.
 *
 * <p><strong>Container elements.</strong> A {@code List}/array value's element constraints ({@link
 * PropertyDescriptor#getConstrainedContainerElementTypes()}, type-argument index 0) merge onto the
 * property's {@code items} subschema when that subschema is an inline object; a {@code Map} value's
 * element position is not described by the generator today, so it is left unchanged, matching the
 * pre-existing gap.
 */
final class MetadataConstraintSource implements ConstraintSource {

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
    public boolean disablesGeneratorJakartaModule() {
        return true;
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
        // A setter or builder method: the same join a field or getter uses, on the built type.
        return renderProperty(builtClass, javaName, kind);
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
        return new ResolvedConstraints(mergeItems(own.keywords, items), own.required);
    }

    private ResolvedConstraints forParameter(
            Class<?> builtClass, AnnotatedParameter parameter, ConstraintValueKind kind) {
        java.lang.reflect.Member owner =
                parameter.getOwner() == null ? null : parameter.getOwner().getMember();
        if (!(owner instanceof Constructor<?> constructor)) {
            // A static-factory creator: Bean Validation exposes constrained constructors only.
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
        return new ResolvedConstraints(rendered.keywords, rendered.required);
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
            if (!rendered.keywords.isEmpty()) {
                return rendered.keywords;
            }
        }
        return Map.of();
    }

    private static Map<String, Object> mergeItems(Map<String, Object> keywords, Map<String, Object> items) {
        if (items.isEmpty()) {
            return keywords;
        }
        Map<String, Object> merged = new LinkedHashMap<>(keywords);
        merged.put("items", items);
        return merged;
    }

    /** One property's, parameter's, or container element's group-filtered, composition-flattened render. */
    private record Rendered(Map<String, Object> keywords, boolean required) {}

    private static Rendered render(Set<ConstraintDescriptor<?>> descriptors, ConstraintValueKind kind, String label) {
        Map<String, Object> keywords = new LinkedHashMap<>();
        boolean[] required = {false};
        for (ConstraintDescriptor<?> descriptor : descriptors) {
            renderOne(descriptor, kind, label, keywords, required);
        }
        return new Rendered(keywords, required[0]);
    }

    private static void renderOne(
            ConstraintDescriptor<?> descriptor,
            ConstraintValueKind kind,
            String label,
            Map<String, Object> keywords,
            boolean[] required) {
        if (!appliesInDefaultGroup(descriptor)) {
            return;
        }
        if (!descriptor.getComposingConstraints().isEmpty()) {
            // A composed constraint (e.g. a custom @Code meta-annotated with @Size + @Pattern): the
            // composing annotation itself is not one of the keyword-bearing types below, so descend
            // into its leaves instead, recursively.
            for (ConstraintDescriptor<?> leaf : descriptor.getComposingConstraints()) {
                renderOne(leaf, kind, label, keywords, required);
            }
        }
        String simpleName = descriptor.getAnnotation().annotationType().getSimpleName();
        Map<String, Object> attributes = descriptor.getAttributes();
        boolean array = kind == ConstraintValueKind.ARRAY;
        boolean map = kind == ConstraintValueKind.MAP;
        switch (simpleName) {
            case "NotNull" -> required[0] = true;
            case "NotEmpty", "NotBlank" -> {
                // Matches victools' own isNullable() exactly (confirmed by disassembly): @NotNull,
                // @NotBlank, and @NotEmpty are treated identically — any one of the three is
                // sufficient for NOT_NULLABLE_FIELD_IS_REQUIRED to mark the property required.
                required[0] = true;
                String minKeyword = array ? "minItems" : map ? "minProperties" : "minLength";
                keywords.putIfAbsent(minKeyword, 1);
            }
            case "Size" -> {
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
            case "Min" -> keywords.put("minimum", (Long) attributes.get("value"));
            case "Max" -> keywords.put("maximum", (Long) attributes.get("value"));
            case "DecimalMin" -> {
                boolean inclusive = (Boolean) attributes.getOrDefault("inclusive", Boolean.TRUE);
                keywords.put(
                        inclusive ? "minimum" : "exclusiveMinimum", new BigDecimal((String) attributes.get("value")));
            }
            case "DecimalMax" -> {
                boolean inclusive = (Boolean) attributes.getOrDefault("inclusive", Boolean.TRUE);
                keywords.put(
                        inclusive ? "maximum" : "exclusiveMaximum", new BigDecimal((String) attributes.get("value")));
            }
            case "Positive" -> keywords.put("exclusiveMinimum", BigDecimal.ZERO);
            case "PositiveOrZero" -> keywords.put("minimum", BigDecimal.ZERO);
            case "Negative" -> keywords.put("exclusiveMaximum", BigDecimal.ZERO);
            case "NegativeOrZero" -> keywords.put("maximum", BigDecimal.ZERO);
            case "Pattern" -> keywords.put("pattern", renderPattern(attributes, label));
            case "Email" -> keywords.put("format", "email");
            case "Length" -> {
                Long min = asLong(attributes.get("min"));
                Long max = asLong(attributes.get("max"));
                if (min != null && min != 0) {
                    keywords.put("minLength", min);
                }
                if (max != null && max != Integer.MAX_VALUE) {
                    keywords.put("maxLength", max);
                }
            }
            case "Range" -> {
                Long min = asLong(attributes.get("min"));
                Long max = asLong(attributes.get("max"));
                if (min != null) {
                    keywords.put("minimum", BigDecimal.valueOf(min));
                }
                if (max != null) {
                    keywords.put("maximum", BigDecimal.valueOf(max));
                }
            }
            case "URL" -> keywords.put("format", "uri");
            default -> {
                if (descriptor.getComposingConstraints().isEmpty()) {
                    LOG.log(
                            Level.DEBUG,
                            "Bean Validation constraint @{0} on {1} has no JSON Schema rendering; skipped",
                            simpleName,
                            label);
                }
            }
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
