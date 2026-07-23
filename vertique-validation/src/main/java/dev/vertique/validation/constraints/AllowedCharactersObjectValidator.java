// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;
import dev.vertique.core.validation.SkipAllowedCharacters;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ValidationException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
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
        return validateObject(value, policy, context, "", visited);
    }

    // --- Object traversal ---

    /**
     * Traverses the fields of the object and validates each string-valued field.
     * Uses an identity-based visited set to prevent infinite recursion on cyclic graphs.
     *
     * @param obj        the object whose fields are traversed
     * @param policy     the character policy to apply to each string
     * @param context    the constraint validator context
     * @param pathPrefix dot-separated path prefix for nested nodes
     * @param visited    identity-based visited set to detect cycles
     * @return {@code true} if all fields pass; {@code false} if any violation is found
     */
    private boolean validateObject(
            Object obj,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            String pathPrefix,
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
                    String fieldPath =
                            pathPrefix.isEmpty() ? component.getName() : pathPrefix + "." + component.getName();
                    if (!validateValue(fieldValue, policy, context, fieldPath, visited)) {
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
                        String fieldPath = pathPrefix.isEmpty() ? field.getName() : pathPrefix + "." + field.getName();
                        if (!validateValue(fieldValue, policy, context, fieldPath, visited)) {
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
     * @param path    the dot-separated path for this value
     * @param visited identity-based visited set to detect cycles
     * @return {@code true} if the value passes; {@code false} if any violation is found
     */
    private boolean validateValue(
            Object value,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            String path,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return validateString(s, policy, context, path);
        }
        if (value instanceof Collection<?> collection) {
            return validateCollection(collection, policy, context, path, visited);
        }
        if (value instanceof Map<?, ?> map) {
            return validateMap(map, policy, context, path, visited);
        }
        // Recurse into application DTO types only
        if (isTraversableDto(value.getClass())) {
            return validateObject(value, policy, context, path, visited);
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
     * @param path       the path of the collection field
     * @return {@code true} if all elements pass; {@code false} otherwise
     */
    private boolean validateCollection(
            Collection<?> collection,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            String path,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        boolean valid = true;
        int i = 0;
        for (Object element : collection) {
            String elementPath = path + "[" + i + "]";
            if (!validateValue(element, policy, context, elementPath, visited)) {
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
     * @param path    the path of the map field
     * @param visited identity-based visited set to detect cycles
     * @return {@code true} if all values pass; {@code false} otherwise
     */
    private boolean validateMap(
            Map<?, ?> map,
            CharacterPolicy policy,
            ConstraintValidatorContext context,
            String path,
            java.util.IdentityHashMap<Object, Boolean> visited) {
        boolean valid = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String entryPath = path + "[" + entry.getKey() + "]";
            if (!validateValue(entry.getValue(), policy, context, entryPath, visited)) {
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
     * @param path    the dot-separated path for this value
     * @return {@code true} if the value passes; {@code false} if a violation was added
     */
    private boolean validateString(
            String value, CharacterPolicy policy, ConstraintValidatorContext context, String path) {
        InputValueContext inputCtx = new InputValueContext(InputLocation.BODY, path, path, Object.class);
        CharacterPolicyResult result = policy.validate(value, inputCtx);
        if (!result.valid()) {
            String message = "contains characters not allowed by " + policyClass.getSimpleName()
                    + (result.reason() != null ? ": " + result.reason() : "");
            context.buildConstraintViolationWithTemplate(message)
                    .addPropertyNode(path)
                    .addConstraintViolation();
            return false;
        }
        return true;
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
