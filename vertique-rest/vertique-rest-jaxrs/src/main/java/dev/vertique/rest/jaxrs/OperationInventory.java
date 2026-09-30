// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the flattened input inventory and the response shape of one resource method, for the
 * {@link dev.vertique.rest.jaxrs.publication.OperationDetail} a publication sink receives.
 *
 * <p>Every fact is read from the {@link ResourceMethodMeta}, its {@link ResourceMethodMeta.ParamMeta}
 * entries, and the {@link BeanParamFieldMeta} list {@link ParameterExtractor#beanParamFields} returns
 * for a composite, which both descriptor paths populate; the only other reads are a record
 * composite's component, accessor, and backing field, the composite class's own {@code @Hidden}, and
 * the annotation types' own meta-annotations. No branch depends on which descriptor path produced
 * the metadata.
 *
 * <p>An input is hidden by its own {@code @Hidden}, {@code @Parameter(hidden = true)}, or
 * {@code @Schema(hidden = true)}; by the composite that binds it; or by a hidden method-level parameter
 * entry, read from {@link ResourceMethodMeta#methodAnnotations()}: a {@code @Parameter} on the
 * method, an entry of a {@code @Parameters} container, an entry of {@code @Operation(parameters =
 * ...)}, or an entry carried by a composed annotation (one meta level deep). Such an entry names a
 * parameter or composite-field input by exact name and by location, and an entry that declares no
 * location names that input at every location. A hidden entry that names no input fails the build of the
 * inventory; a visible entry is ignored.
 *
 * <p>Bean Validation annotations and groups are matched by type name in the {@code
 * jakarta.validation} namespace only, so this module needs no validation dependency; {@code
 * javax.validation} annotations are not recognized. Hiding markers are matched by class.
 */
final class OperationInventory {

    private static final String CONSTRAINT = "jakarta.validation.Constraint";
    private static final String VALID = "jakarta.validation.Valid";
    private static final String GROUP_SEQUENCE = "jakarta.validation.GroupSequence";
    private static final String CONVERT_GROUP = "jakarta.validation.groups.ConvertGroup";
    private static final String CONVERT_GROUP_LIST = "jakarta.validation.groups.ConvertGroup$List";
    private static final String DEFAULT_GROUP = "jakarta.validation.groups.Default";
    private static final String NOT_NULL = "jakarta.validation.constraints.NotNull";
    private static final String NOT_BLANK = "jakarta.validation.constraints.NotBlank";
    private static final String NOT_EMPTY = "jakarta.validation.constraints.NotEmpty";

    /** The longest annotation-supplied text a startup message quotes before cutting it. */
    private static final int MAX_MESSAGE_NAME_LENGTH = 128;

    private OperationInventory() {}

    /**
     * Lists every input the method binds, in method-parameter declaration order: one binding per
     * path, query, header, cookie, or form parameter, one for the body, and one per bindable field
     * of a {@code @BeanParam} or {@code @RequestParams} composite, in the binding owner's field
     * order, at the composite parameter's position. Context, precondition, and unnamed multipart
     * parameters produce no binding.
     *
     * @param meta           the resource method's metadata
     * @param schemas        the schema copies captured for the operation
     * @param gateInstalled  whether a request-validation gate was installed for the operation
     * @param validatorBound whether a Bean Validation implementation checks the method's arguments
     * @return the inventory, in declaration order
     * @throws RestConfigurationException when a hidden method-level parameter entry names no input the
     *                                    method binds
     */
    static List<InputBinding> inputs(
            ResourceMethodMeta meta, CapturedSchemas schemas, boolean gateInstalled, boolean validatorBound) {
        Validation validation = new Validation(validatorBound, meta.validationGroups(), sequenceInvolved(meta));
        List<ResourceMethodMeta.ParamMeta> params = meta.params();
        List<InputBinding> inputs = new ArrayList<>();
        for (int index = 0; index < params.size(); index++) {
            ResourceMethodMeta.ParamMeta param = params.get(index);
            switch (param.source()) {
                case PATH, QUERY, HEADER, COOKIE, FORM -> {
                    ParamLocation location = toLocation(param.source());
                    List<Annotation> annotations = annotationsOf(param);
                    inputs.add(new InputBinding(
                            Origin.PARAMETER,
                            location,
                            param.name(),
                            typeOf(param),
                            param.defaultValue(),
                            requiredness(param, location, annotations, null, validation),
                            ownMarker(annotations),
                            gateInstalled && schemas.parameters().containsKey(new InputKey(location, param.name())),
                            annotations,
                            index,
                            null));
                }
                case BODY -> {
                    List<Annotation> annotations = annotationsOf(param);
                    inputs.add(new InputBinding(
                            Origin.BODY,
                            null,
                            null,
                            typeOf(param),
                            param.defaultValue(),
                            Requiredness.UNKNOWN,
                            ownMarker(annotations),
                            gateInstalled && schemas.body() != null,
                            annotations,
                            index,
                            null));
                }
                case BEAN_PARAM -> addCompositeFields(inputs, param, index, validation);
                default -> {
                    // CONTEXT and PRECONDITIONS are not request inputs; FILE_UPLOADS and
                    // ENTITY_PARTS bind unnamed part lists, which a binding cannot represent.
                }
            }
        }
        applyMethodHiding(meta, inputs);
        return Collections.unmodifiableList(inputs);
    }

    /**
     * Returns the response facts of the method: its generic return type, unresolved; the resource
     * instance's class, against whose generic supertypes a type variable resolves; its future and
     * void flags; its declared produces types; and the output profile id.
     *
     * @param meta             the resource method's metadata
     * @param outputProfileId  the resolved JSON mapper profile id that serializes the response
     * @return the response shape
     */
    static ResponseShape response(ResourceMethodMeta meta, String outputProfileId) {
        return new ResponseShape(
                meta.method().getGenericReturnType(),
                meta.resourceInstance().getClass(),
                meta.returnsFuture(),
                meta.returnsVoid(),
                meta.mediaTypes().produces(),
                outputProfileId);
    }

    // ---------------------------------------------------------------------------------------------
    // Composite fields
    // ---------------------------------------------------------------------------------------------

    private static void addCompositeFields(
            List<InputBinding> inputs, ResourceMethodMeta.ParamMeta composite, int index, Validation validation) {
        Class<?> compositeType = composite.type();
        List<Annotation> compositeAnnotations = annotationsOf(composite);
        boolean compositeHidden = compositeParameterMarker(compositeAnnotations) || compositeTypeHidden(compositeType);
        for (BeanParamFieldMeta field : ParameterExtractor.beanParamFields(compositeType)) {
            ResourceMethodMeta.ParamMeta fieldMeta = field.meta();
            if (!isBindable(fieldMeta.source())) {
                continue;
            }
            ParamLocation location = toLocation(fieldMeta.source());
            List<Annotation> memberAnnotations = new ArrayList<>(annotationsOf(fieldMeta));
            for (Annotation extra : recordMemberAnnotations(compositeType, field.name())) {
                if (!memberAnnotations.contains(extra)) {
                    memberAnnotations.add(extra);
                }
            }
            inputs.add(new InputBinding(
                    Origin.COMPOSITE_FIELD,
                    location,
                    fieldMeta.name(),
                    typeOf(fieldMeta),
                    fieldMeta.defaultValue(),
                    requiredness(fieldMeta, location, memberAnnotations, compositeAnnotations, validation),
                    ownMarker(memberAnnotations) || compositeHidden,
                    false,
                    memberAnnotations,
                    index,
                    compositeType));
        }
    }

    /**
     * For a record composite, the annotations on the component named {@code member}, its accessor,
     * and its backing field; empty for any other composite.
     */
    private static List<Annotation> recordMemberAnnotations(Class<?> compositeType, String member) {
        if (!compositeType.isRecord()) {
            return List.of();
        }
        List<Annotation> annotations = new ArrayList<>();
        for (RecordComponent component : compositeType.getRecordComponents()) {
            if (component.getName().equals(member)) {
                Collections.addAll(annotations, component.getAnnotations());
                Collections.addAll(annotations, component.getAccessor().getAnnotations());
                try {
                    Field backing = compositeType.getDeclaredField(member);
                    Collections.addAll(annotations, backing.getAnnotations());
                } catch (NoSuchFieldException e) {
                    // Every record component has a backing field of its name; nothing more to read.
                }
            }
        }
        return annotations;
    }

    // ---------------------------------------------------------------------------------------------
    // Hidden
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the annotations carry {@code @Hidden}, {@code @Parameter(hidden = true)}, or
     * {@code @Schema(hidden = true)}.
     */
    private static boolean ownMarker(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Hidden) {
                return true;
            }
            if (annotation instanceof io.swagger.v3.oas.annotations.Parameter parameter && parameter.hidden()) {
                return true;
            }
            if (annotation instanceof Schema schema && schema.hidden()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the composite method parameter carries {@code @Parameter(hidden = true)} or
     * {@code @Schema(hidden = true)}.
     */
    private static boolean compositeParameterMarker(List<Annotation> compositeAnnotations) {
        for (Annotation annotation : compositeAnnotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.Parameter parameter && parameter.hidden()) {
                return true;
            }
            if (annotation instanceof Schema schema && schema.hidden()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the composite class itself carries {@code @Hidden}; a superclass's does not count. */
    private static boolean compositeTypeHidden(Class<?> compositeType) {
        return compositeType.getAnnotation(Hidden.class) != null;
    }

    /**
     * Hides every parameter and composite-field input a hidden method-level parameter entry names,
     * matching by name, exactly, and by location, where an entry without a location matches every
     * location; an entry that matches several inputs hides each of them. A visible entry changes
     * nothing, and an input hidden by its own markers stays hidden.
     *
     * @throws RestConfigurationException when a hidden entry matches no input
     */
    private static void applyMethodHiding(ResourceMethodMeta meta, List<InputBinding> inputs) {
        for (io.swagger.v3.oas.annotations.Parameter entry : methodParameterEntries(meta.methodAnnotations())) {
            if (!entry.hidden()) {
                continue;
            }
            boolean matched = false;
            for (int i = 0; i < inputs.size(); i++) {
                InputBinding binding = inputs.get(i);
                if ((binding.origin() == Origin.PARAMETER || binding.origin() == Origin.COMPOSITE_FIELD)
                        && entry.name().equals(binding.name())
                        && locationMatches(entry.in(), binding.location())) {
                    matched = true;
                    inputs.set(i, hidden(binding));
                }
            }
            if (!matched) {
                throw new RestConfigurationException("Operation '" + bounded(meta.operationId())
                        + "' declares a hidden method-level @Parameter named '" + bounded(entry.name()) + "' "
                        + describeLocation(entry.in())
                        + ", but binds no input of that name and location; name an input the method binds,"
                        + " or remove the entry"
                        + (entry.in() == ParameterIn.HEADER
                                ? "; header names are matched case-sensitively, write the name exactly as in"
                                        + " @HeaderParam"
                                : "")
                        + ".");
            }
        }
    }

    /**
     * The swagger parameter entries declared on the method: each {@code @Parameter}, each entry of a
     * {@code @Parameters} container, and each entry of {@code @Operation(parameters = ...)}, plus
     * those carried by a composed annotation, one meta level deep: the {@code @Parameter} and {@code
     * @Parameters} entries and the {@code @Operation(parameters = ...)} entries present on the
     * method annotation's own type.
     */
    private static List<io.swagger.v3.oas.annotations.Parameter> methodParameterEntries(
            List<Annotation> methodAnnotations) {
        List<io.swagger.v3.oas.annotations.Parameter> entries = new ArrayList<>();
        for (Annotation annotation : methodAnnotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.Parameter parameter) {
                entries.add(parameter);
            } else if (annotation instanceof Parameters container) {
                Collections.addAll(entries, container.value());
            } else if (annotation instanceof Operation operation) {
                Collections.addAll(entries, operation.parameters());
            } else {
                Class<? extends Annotation> composed = annotation.annotationType();
                Collections.addAll(
                        entries, composed.getAnnotationsByType(io.swagger.v3.oas.annotations.Parameter.class));
                Operation meta = composed.getAnnotation(Operation.class);
                if (meta != null) {
                    Collections.addAll(entries, meta.parameters());
                }
            }
        }
        return entries;
    }

    /** Whether an entry's location matches a bound location; the default location matches every one. */
    private static boolean locationMatches(ParameterIn in, @Nullable ParamLocation location) {
        return switch (in) {
            case DEFAULT -> true;
            case QUERY -> location == ParamLocation.QUERY;
            case HEADER -> location == ParamLocation.HEADER;
            case COOKIE -> location == ParamLocation.COOKIE;
            case PATH -> location == ParamLocation.PATH;
        };
    }

    private static String describeLocation(ParameterIn in) {
        return in == ParameterIn.DEFAULT ? "at any location" : "in " + in.name();
    }

    /** The binding with its hidden flag set; every other component is unchanged. */
    private static InputBinding hidden(InputBinding binding) {
        return new InputBinding(
                binding.origin(),
                binding.location(),
                binding.name(),
                binding.type(),
                binding.defaultValue(),
                binding.requiredness(),
                true,
                binding.schemaEnforced(),
                binding.annotations(),
                binding.methodParameterIndex(),
                binding.compositeType());
    }

    /**
     * Annotation-supplied text made safe for a startup message: control characters become {@code ?},
     * and text beyond {@value #MAX_MESSAGE_NAME_LENGTH} characters is cut and marked with {@code ...}.
     */
    private static String bounded(@Nullable String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_MESSAGE_NAME_LENGTH) + 3);
        for (int i = 0; i < text.length() && i < MAX_MESSAGE_NAME_LENGTH; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        if (text.length() > MAX_MESSAGE_NAME_LENGTH) {
            out.append("...");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Requiredness
    // ---------------------------------------------------------------------------------------------

    /**
     * The validation facts shared by every input of one method.
     *
     * @param validatorBound   whether a Bean Validation implementation checks the arguments
     * @param requestedGroups  the method's validation groups, or {@code null} for {@code Default}
     * @param sequenceInvolved whether a group sequence takes part in the method's validation
     */
    private record Validation(
            boolean validatorBound, @Nullable Class<?>[] requestedGroups, boolean sequenceInvolved) {}

    /**
     * Classifies whether the runtime certainly rejects, certainly accepts, or may reject a missing
     * value, by the first matching rule: a path input is required; a primitive method parameter
     * without a default value is unknown, whether or not a validator is bound, since conversion and
     * invocation, not validation, decide the outcome for an absent value, and this classification
     * does not evaluate them; with no validator bound, a default value, or no constraint, an input
     * is not required; a non-array collection binds an empty collection, and a primitive composite
     * field binds its type's default value, so either is not required with only {@code @NotNull}
     * and unknown otherwise;
     * an input is required with an active null-rejecting constraint when no group sequence is
     * involved and, for a composite field, validation cascades into the composite; every other case
     * is unknown. A constraint whose groups cannot be read counts as inactive.
     *
     * @param compositeAnnotations the composite method parameter's annotations, or {@code null} for
     *                             a method parameter
     */
    private static Requiredness requiredness(
            ResourceMethodMeta.ParamMeta param,
            ParamLocation location,
            List<Annotation> annotations,
            @Nullable List<Annotation> compositeAnnotations,
            Validation validation) {
        if (location == ParamLocation.PATH) {
            return Requiredness.REQUIRED;
        }
        if (compositeAnnotations == null && isPrimitive(param) && !hasDefault(param)) {
            return Requiredness.UNKNOWN;
        }
        List<Annotation> constraints = constraintsOf(annotations);
        if (!validation.validatorBound() || hasDefault(param) || constraints.isEmpty()) {
            return Requiredness.NOT_REQUIRED;
        }
        if (isNonArrayCollection(param) || (compositeAnnotations != null && isPrimitive(param))) {
            return onlyNotNull(constraints) ? Requiredness.NOT_REQUIRED : Requiredness.UNKNOWN;
        }
        if (hasActiveNullRejecting(constraints, validation.requestedGroups())
                && !validation.sequenceInvolved()
                && (compositeAnnotations == null || compositeCascades(compositeAnnotations))) {
            return Requiredness.REQUIRED;
        }
        return Requiredness.UNKNOWN;
    }

    /** Whether the input binds a {@code @DefaultValue} when absent. */
    private static boolean hasDefault(ResourceMethodMeta.ParamMeta param) {
        return param.defaultValue() != null;
    }

    /** Whether the input's type is a primitive type, which cannot hold {@code null}. */
    private static boolean isPrimitive(ResourceMethodMeta.ParamMeta param) {
        return param.type().isPrimitive();
    }

    /** Whether the input is a non-array collection, which binds an empty collection when absent. */
    private static boolean isNonArrayCollection(ResourceMethodMeta.ParamMeta param) {
        return param.componentType() != null && !param.type().isArray();
    }

    /** Whether every constraint is {@code @NotNull}. */
    private static boolean onlyNotNull(List<Annotation> constraints) {
        for (Annotation constraint : constraints) {
            if (!constraint.annotationType().getName().equals(NOT_NULL)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a {@code @NotNull}, {@code @NotBlank}, or {@code @NotEmpty} is in an activated group; a
     * constraint whose groups cannot be read does not count.
     */
    private static boolean hasActiveNullRejecting(List<Annotation> constraints, @Nullable Class<?>[] requestedGroups) {
        for (Annotation constraint : constraints) {
            if (!isNullRejecting(constraint)) {
                continue;
            }
            Class<?>[] constraintGroups = groupsOf(constraint);
            if (constraintGroups != null && anyGroupActive(constraintGroups, requestedGroups)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullRejecting(Annotation constraint) {
        String name = constraint.annotationType().getName();
        return name.equals(NOT_NULL) || name.equals(NOT_BLANK) || name.equals(NOT_EMPTY);
    }

    /** Whether validation cascades into the composite: {@code @Valid} and no {@code @ConvertGroup}. */
    private static boolean compositeCascades(List<Annotation> compositeAnnotations) {
        boolean valid = false;
        for (Annotation annotation : compositeAnnotations) {
            String name = annotation.annotationType().getName();
            if (name.equals(CONVERT_GROUP) || name.equals(CONVERT_GROUP_LIST)) {
                return false;
            }
            if (name.equals(VALID)) {
                valid = true;
            }
        }
        return valid;
    }

    /**
     * The constraint annotations among {@code annotations}: those whose type is meta-annotated
     * {@code @Constraint}, and each such annotation inside a repeatable container, judged on its own.
     */
    private static List<Annotation> constraintsOf(List<Annotation> annotations) {
        List<Annotation> constraints = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (isConstraint(annotation.annotationType())) {
                constraints.add(annotation);
            } else {
                for (Annotation contained : containedConstraints(annotation)) {
                    constraints.add(contained);
                }
            }
        }
        return constraints;
    }

    private static boolean isConstraint(Class<? extends Annotation> annotationType) {
        return hasAnnotationNamed(annotationType.getAnnotations(), CONSTRAINT);
    }

    /**
     * The constraints held by a repeatable container, one whose {@code value()} is an array of
     * constraint annotations; empty for any other annotation.
     */
    private static List<Annotation> containedConstraints(Annotation annotation) {
        Method value;
        try {
            value = annotation.annotationType().getMethod("value");
        } catch (NoSuchMethodException e) {
            return List.of();
        }
        Class<?> returnType = value.getReturnType();
        if (!returnType.isArray()
                || !returnType.getComponentType().isAnnotation()
                || !isConstraint(returnType.getComponentType().asSubclass(Annotation.class))) {
            return List.of();
        }
        try {
            value.setAccessible(true);
            return List.of((Annotation[]) value.invoke(annotation));
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The constraint's {@code groups()}, read reflectively; empty means {@code Default}, and
     * {@code null} means the groups cannot be read.
     */
    private static @Nullable Class<?>[] groupsOf(Annotation constraint) {
        try {
            Method groups = constraint.annotationType().getMethod("groups");
            groups.setAccessible(true);
            Object value = groups.invoke(constraint);
            return value instanceof Class<?>[] classes ? classes : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }

    /** Whether any of a constraint's groups is activated by the requested groups. */
    private static boolean anyGroupActive(Class<?>[] constraintGroups, @Nullable Class<?>[] requestedGroups) {
        if (constraintGroups.length == 0) {
            return defaultActive(requestedGroups);
        }
        for (Class<?> group : constraintGroups) {
            if (groupActive(group, requestedGroups)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether constraint group {@code group} is active: for no requested groups, when it is {@code
     * Default}; otherwise when a requested group is {@code group} or extends it.
     */
    private static boolean groupActive(Class<?> group, @Nullable Class<?>[] requestedGroups) {
        if (requestedGroups == null) {
            return group.getName().equals(DEFAULT_GROUP);
        }
        for (Class<?> requested : requestedGroups) {
            if (group.isAssignableFrom(requested)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the {@code Default} group is active: requested implicitly, or extended by a requested group. */
    private static boolean defaultActive(@Nullable Class<?>[] requestedGroups) {
        if (requestedGroups == null) {
            return true;
        }
        for (Class<?> requested : requestedGroups) {
            if (extendsTypeNamed(requested, DEFAULT_GROUP)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code type} is, or extends, a class or interface named {@code name}. */
    private static boolean extendsTypeNamed(@Nullable Class<?> type, String name) {
        if (type == null) {
            return false;
        }
        if (type.getName().equals(name) || extendsTypeNamed(type.getSuperclass(), name)) {
            return true;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (extendsTypeNamed(implemented, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a group sequence takes part in the method's validation: a requested group carries
     * {@code @GroupSequence}, or the resource class, a superclass, or an interface does, which
     * redefines {@code Default}. Class annotations are those resolved for the resource class and its
     * hierarchy.
     */
    private static boolean sequenceInvolved(ResourceMethodMeta meta) {
        Class<?>[] requested = meta.validationGroups();
        if (requested != null) {
            for (Class<?> group : requested) {
                if (hasAnnotationNamed(group.getAnnotations(), GROUP_SEQUENCE)) {
                    return true;
                }
            }
        }
        return hasAnnotationNamed(meta.classAnnotations().toArray(new Annotation[0]), GROUP_SEQUENCE);
    }

    private static boolean hasAnnotationNamed(Annotation[] annotations, String name) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------

    private static List<Annotation> annotationsOf(ResourceMethodMeta.ParamMeta param) {
        return List.of(param.annotationsLazy().get());
    }

    private static Type typeOf(ResourceMethodMeta.ParamMeta param) {
        return param.genericType() != null ? param.genericType() : param.type();
    }

    private static boolean isBindable(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE, FORM -> true;
            default -> false;
        };
    }

    private static ParamLocation toLocation(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH -> ParamLocation.PATH;
            case QUERY -> ParamLocation.QUERY;
            case HEADER -> ParamLocation.HEADER;
            case COOKIE -> ParamLocation.COOKIE;
            case FORM -> ParamLocation.FORM;
            default -> throw new IllegalStateException("Non-bindable parameter source: " + source);
        };
    }
}
