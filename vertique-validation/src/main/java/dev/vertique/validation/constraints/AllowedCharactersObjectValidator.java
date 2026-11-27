// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.input.InputLocation;
import dev.vertique.core.input.InputValueContext;
import dev.vertique.core.util.AnnotationResolver;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.ContainerElementNodeBuilderDefinedContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;
import jakarta.validation.ValidationException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Jakarta Bean Validation {@link ConstraintValidator} for object-level {@link AllowedCharacters}
 * constraints.
 *
 * <p>When {@code @AllowedCharacters} is placed on a type, this validator is invoked to traverse
 * the object graph and apply the character policy to all reachable {@link String},
 * {@code Collection<String>}, and {@code Map<?, String>} values.
 *
 * <p>Traversal rules:
 * <ul>
 *   <li>Fields annotated with {@link SkipAllowedCharacters} are excluded from validation.</li>
 *   <li>Fields annotated with their own {@code @AllowedCharacters} are excluded — they handle
 *       their own policy at the field level.</li>
 *   <li>Static fields are always excluded.</li>
 *   <li>For records, {@link RecordComponent} accessor annotations are inspected.</li>
 * </ul>
 *
 * <p>Null values and plain {@link CharSequence} values are treated as valid (the latter is
 * handled by {@link AllowedCharactersValidator} at the field level).
 *
 * @see AllowedCharacters
 * @see AllowedCharactersValidator
 * @see CharacterPolicy
 * @see SkipAllowedCharacters
 */
public class AllowedCharactersObjectValidator implements ConstraintValidator<AllowedCharacters, Object> {

    private static final String LIST_ELEMENT_NODE = "<list element>";
    private static final String MAP_VALUE_NODE = "<map value>";

    /** One step of the traversal path: a property, a collection position or a map entry. */
    private sealed interface Step {}

    private record PropertyStep(String name) implements Step {}

    private record IndexStep(int index) implements Step {}

    private record EntryStep(Object key) implements Step {}

    /**
     * The traversal path to a value: the display text handed to the policy and the steps used to
     * build the violation's property path.
     */
    private record Trail(String text, List<Step> steps) {
        static final Trail ROOT = new Trail("", List.of());

        Trail property(String name) {
            return new Trail(text.isEmpty() ? name : text + "." + name, append(new PropertyStep(name)));
        }

        Trail index(int index) {
            return new Trail(text + "[" + index + "]", append(new IndexStep(index)));
        }

        Trail entry(Object key) {
            return new Trail(text + "[" + key + "]", append(new EntryStep(key)));
        }

        private List<Step> append(Step step) {
            List<Step> next = new ArrayList<>(steps.size() + 1);
            next.addAll(steps);
            next.add(step);
            return next;
        }
    }

    private final @Nullable CharacterPolicyResolver resolver;
    private Class<? extends CharacterPolicy> policyClass;

    /** No-arg constructor for Hibernate Validator fallback (no Dagger context). */
    public AllowedCharactersObjectValidator() {
        this.resolver = null;
    }

    /**
     * Constructor with Dagger-managed resolver for custom policy resolution.
     *
     * @param resolver the resolver to use for policy instantiation
     */
    public AllowedCharactersObjectValidator(CharacterPolicyResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Captures the policy class from the constraint annotation.
     *
     * @param annotation the {@link AllowedCharacters} annotation instance
     */
    @Override
    public void initialize(AllowedCharacters annotation) {
        this.policyClass = annotation.policy();
    }

    /**
     * Validates the object by traversing its string-valued fields and applying the character policy.
     *
     * <p>Returns {@code true} for {@code null} values and {@link CharSequence} values (the latter
     * are delegated to {@link AllowedCharactersValidator}). Disables the default constraint
     * violation and adds per-field violations with path information on failure.
     *
     * @param value   the object to validate; may be {@code null}
     * @param context the constraint validator context for building custom violations
     * @return {@code true} if all reachable string values pass the policy; {@code false} otherwise
     */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // CharSequence targets are handled by AllowedCharactersValidator
        if (value instanceof CharSequence) {
            return true;
        }

        CharacterPolicy policy = resolvePolicy(policyClass);
        context.disableDefaultConstraintViolation();
        java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<>();
        return validateObject(value, policy, context, Trail.ROOT, visited);
    }

    // --- Object traversal ---

    /**
     * Traverses the fields of the object and validates each string-valued field.
     * Uses an identity-based visited set to prevent infinite recursion on cyclic graphs.
     *
     * @param obj        the object whose fields are traversed
     * @param policy     the character policy to apply to each string
     * @param context    the constraint validator context
     * @param trail      the path to the object
     * @param visited    identity-based visited set to detect cycles
     * @return {@code true} if all fields pass; {@code false} if any violation is found
     */
    private boolean validateObject(
            Object obj,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            Trail trail,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        if (visited.containsKey(obj)) {
            return true; // Cycle detected — skip
        }
        visited.put(obj, Boolean.TRUE);

        boolean valid = true;
        Class<?> clazz = obj.getClass();

        if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                if (shouldSkipComponent(component)) {
                    continue;
                }
                try {
                    Object fieldValue = component.getAccessor().invoke(obj);
                    if (!validateValue(fieldValue, policy, context, trail.property(component.getName()), visited)) {
                        valid = false;
                    }
                } catch (Exception e) {
                    // Skip inaccessible components
                }
            }
        } else {
            for (Class<?> current = clazz;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (shouldSkipField(field)) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object fieldValue = field.get(obj);
                        if (!validateValue(fieldValue, policy, context, trail.property(field.getName()), visited)) {
                            valid = false;
                        }
                    } catch (Exception e) {
                        // Skip inaccessible fields (e.g., JDK internal types)
                    }
                }
            }
        }

        return valid;
    }

    // --- Field value dispatch ---

    /**
     * Dispatches validation based on the runtime type of {@code value}.
     *
     * <p>Handles {@link String}, {@link Collection}, {@link Map}, and nested DTO types.
     * Only recurses into application DTO types (records and beans whose package does not start
     * with {@code java.} or {@code javax.}). JDK types, primitives, boxed primitives, enums,
     * and other non-DTO types are skipped.
     *
     * @param value   the field value to validate
     * @param policy  the character policy
     * @param context the constraint validator context
     * @param trail   the path to this value
     * @param visited identity-based visited set to detect cycles
     * @return {@code true} if the value passes; {@code false} if any violation is found
     */
    private boolean validateValue(
            Object value,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            Trail trail,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return validateString(s, policy, context, trail);
        }
        if (value instanceof Collection<?> collection) {
            return validateCollection(collection, policy, context, trail, visited);
        }
        if (value instanceof Map<?, ?> map) {
            return validateMap(map, policy, context, trail, visited);
        }
        // Recurse into application DTO types only
        if (isTraversableDto(value.getClass())) {
            return validateObject(value, policy, context, trail, visited);
        }
        return true;
    }

    /**
     * Returns {@code true} if the type is an application DTO that should be recursively traversed.
     * Excludes JDK types, primitives, boxed primitives, enums, and other non-DTO types.
     */
    private static boolean isTraversableDto(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()) return false;
        String name = type.getName();
        // Exclude JDK and standard library types
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) return false;
        // Exclude common non-DTO types
        if (type == Boolean.class
                || type == Byte.class
                || type == Character.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class) {
            return false;
        }
        return true;
    }

    /**
     * Validates each element in a collection by delegating to {@link #validateValue}, which
     * dispatches to the appropriate handler (String, nested Collection, Map, or nested object).
     * This ensures that nested DTOs inside a collection are recursively traversed.
     *
     * @param collection the collection to traverse
     * @param policy     the character policy
     * @param context    the constraint validator context
     * @param trail      the path of the collection field
     * @param visited    identity-based visited set to detect cycles
     * @return {@code true} if all elements pass; {@code false} otherwise
     */
    private boolean validateCollection(
            Collection<?> collection,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            Trail trail,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        boolean valid = true;
        int i = 0;
        for (Object element : collection) {
            if (!validateValue(element, policy, context, trail.index(i), visited)) {
                valid = false;
            }
            i++;
        }
        return valid;
    }

    /**
     * Validates each map value by delegating to {@link #validateValue}, which dispatches to the
     * appropriate handler (String, nested Collection, Map, or nested object). This ensures that
     * nested DTOs stored as map values are recursively traversed.
     *
     * @param map     the map to traverse
     * @param policy  the character policy
     * @param context the constraint validator context
     * @param trail   the path of the map field
     * @param visited identity-based visited set to detect cycles
     * @return {@code true} if all values pass; {@code false} otherwise
     */
    private boolean validateMap(
            Map<?, ?> map,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            Trail trail,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        boolean valid = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!validateValue(entry.getValue(), policy, context, trail.entry(entry.getKey()), visited)) {
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Validates a single string value against the policy and adds a constraint violation if it fails.
     *
     * @param value   the string to validate
     * @param policy  the character policy
     * @param context the constraint validator context
     * @param trail   the path to this value
     * @return {@code true} if the value passes; {@code false} if a violation was added
     */
    private boolean validateString(
            String value, CharacterPolicy policy, ConstraintValidatorContext context, Trail trail) {
        String path = trail.text();
        // Synthetic location: the traversal path is real, but BODY is a placeholder — Bean
        // Validation has no accurate provenance for the value. See CharacterPolicy#validate.
        InputValueContext inputCtx = new InputValueContext(InputLocation.BODY, path, path, Object.class);
        CharacterPolicyResult result = policy.validate(value, inputCtx);
        if (!result.valid()) {
            String message = "contains characters not allowed by " + policyClass.getSimpleName()
                    + (result.reason() != null ? ": " + result.reason() : "");
            addViolation(context, message, trail);
            return false;
        }
        return true;
    }

    /**
     * Adds a violation whose property path mirrors the traversal: a property node per field, and a
     * container-element node (carrying the position) per collection element or map value. A map
     * entry is positioned by key, which the framework's path conversion never reads.
     */
    private static void addViolation(ConstraintValidatorContext context, String message, Trail trail) {
        ConstraintViolationBuilder builder = context.buildConstraintViolationWithTemplate(message);
        NodeBuilderCustomizableContext property = null;
        ContainerElementNodeBuilderDefinedContext element = null;
        for (Step step : trail.steps()) {
            switch (step) {
                case PropertyStep p -> {
                    property = element != null
                            ? element.addPropertyNode(p.name())
                            : property != null ? property.addPropertyNode(p.name()) : builder.addPropertyNode(p.name());
                    element = null;
                }
                case IndexStep i -> {
                    element = nextElement(property, element, LIST_ELEMENT_NODE, Collection.class)
                            .inIterable()
                            .atIndex(i.index());
                    property = null;
                }
                case EntryStep e -> {
                    element = nextElement(property, element, MAP_VALUE_NODE, Map.class)
                            .inIterable()
                            .atKey(e.key());
                    property = null;
                }
            }
        }
        if (element != null) {
            element.addConstraintViolation();
        } else if (property != null) {
            property.addConstraintViolation();
        } else {
            builder.addConstraintViolation();
        }
    }

    private static ContainerElementNodeBuilderCustomizableContext nextElement(
            @Nullable NodeBuilderCustomizableContext property,
            @Nullable ContainerElementNodeBuilderDefinedContext element,
            String name,
            Class<?> containerType) {
        int typeArgumentIndex = containerType == Map.class ? 1 : 0;
        if (element != null) {
            return element.addContainerElementNode(name, containerType, typeArgumentIndex);
        }
        if (property != null) {
            return property.addContainerElementNode(name, containerType, typeArgumentIndex);
        }
        throw new IllegalStateException("a collection position needs an owning property");
    }

    // --- Skip detection ---

    /**
     * Returns {@code true} if the field should be excluded from object-level validation.
     *
     * <p>Excludes fields annotated with {@link SkipAllowedCharacters} or their own
     * {@link AllowedCharacters} (which handles validation at the field level). Meta-annotations
     * composed from either annotation are also recognized.
     *
     * @param field the field to inspect
     * @return {@code true} if the field should be skipped
     */
    private static boolean shouldSkipField(Field field) {
        return AnnotationResolver.findMetaAnnotation(field, SkipAllowedCharacters.class) != null
                || AnnotationResolver.findMetaAnnotation(field, AllowedCharacters.class) != null
                || field.getAnnotationsByType(AllowedCharacters.class).length > 0;
    }

    /**
     * Returns {@code true} if the record component should be excluded from object-level validation.
     *
     * <p>Excludes components annotated with {@link SkipAllowedCharacters} or their own
     * {@link AllowedCharacters} (which handles validation at the field level). Also checks the
     * accessor method, where JAX-RS annotations typically land. Meta-annotations composed from
     * either annotation are also recognized.
     *
     * @param component the record component to inspect
     * @return {@code true} if the component should be skipped
     */
    private static boolean shouldSkipComponent(RecordComponent component) {
        if (AnnotationResolver.findMetaAnnotation(component, SkipAllowedCharacters.class) != null
                || AnnotationResolver.findMetaAnnotation(component, AllowedCharacters.class) != null
                || component.getAnnotationsByType(AllowedCharacters.class).length > 0) {
            return true;
        }
        return AnnotationResolver.findMetaAnnotation(component.getAccessor(), SkipAllowedCharacters.class) != null
                || AnnotationResolver.findMetaAnnotation(component.getAccessor(), AllowedCharacters.class) != null
                || component.getAccessor().getAnnotationsByType(AllowedCharacters.class).length > 0;
    }

    // --- Policy resolution ---

    /**
     * Resolves the policy for the given class.
     *
     * <p>When a {@link CharacterPolicyResolver} was injected at construction time, delegates to
     * it (enabling Dagger-managed policies with injected dependencies). Otherwise falls back to
     * reflection-based instantiation via the public no-arg constructor.
     *
     * @param policyClass the policy class to resolve
     * @return the resolved {@link CharacterPolicy} instance
     * @throws ValidationException if the class cannot be instantiated
     */
    private CharacterPolicy resolvePolicy(Class<? extends CharacterPolicy> policyClass) {
        if (resolver != null) {
            return resolver.resolve(policyClass);
        }
        try {
            return policyClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate CharacterPolicy: " + policyClass.getName(), e);
        }
    }
}
