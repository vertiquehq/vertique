// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;

/**
 * Turns a synthetic publication built by {@link Publications} into the publication the
 * documentation hook receives before it detaches it: every operation's detail carries a {@link
 * StubOperationDescriptor}, and the bindings of an annotated operation carry the real annotation
 * instances and Java types of a fixture method's parameters.
 *
 * <pre>{@code
 * MountPublication attached = MetadataPublications.from(MetadataPublications.mount()
 *                 .operation("GET", "/items", "listItems")
 *                     .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
 *                         .schema(new JsonObject().put("type", "string"))
 *                 .operation("POST", "/items", "createItem")
 *                     .consumes("application/json")
 *                     .body(GeneratedBodies.describe(ItemDto.class))
 *                 .build())
 *         .annotate("listItems", AgreementResource.class, "renamedQuery")
 *         .annotate("createItem", AgreementResource.class, "createWithBody")
 *         .build();
 * }</pre>
 *
 * <p><b>Annotating an operation.</b> {@link #annotate} reads one fixture method: its method
 * annotations through {@code AnnotationResolver.resolveMethodAnnotations} and its class's
 * annotations through {@code AnnotationResolver.resolveClassAnnotations}, so annotations declared on
 * an implemented interface, an interface method, or a superclass count as they do at runtime. Each
 * binding of the operation is matched to the fixture parameter at its method parameter index (the
 * order of the {@link Publications} calls: every {@code param}, {@code formField}, and {@code body}
 * call takes the next index), and takes that parameter's annotations (resolved through {@code
 * AnnotationResolver.resolveParameterAnnotations}, replacing any the builder set) and its generic
 * type. The fixture method must therefore list exactly one parameter per binding, in builder order;
 * a parameter binding's fixture parameter must carry the JAX-RS parameter annotation of the same
 * location and name ({@code @QueryParam("q")} for a query binding {@code q}), and the body's fixture
 * parameter none. Composite fields keep the builder's values. A mismatch throws {@link
 * IllegalStateException}.
 *
 * <p><b>Overrides.</b> The requiredness, hidden flag, and Java type of a binding can be set
 * explicitly; they apply after the fixture is read, so an explicit type wins over the fixture's. The
 * hidden flag is never inferred from a fixture annotation: a binding is hidden only when {@link
 * Publications} or {@link #hidden} says so. Every body binding's requiredness stays {@code
 * UNKNOWN}, as the runtime leaves it.
 *
 * <p><b>Descriptors.</b> Every operation gets a stub descriptor, annotated or not: the consumed media
 * types exactly as the builder recorded them (no default is added), one {@link ParamDescriptor} per
 * parameter binding with its element type (the element of a {@code List}, {@code Set}, {@code
 * SortedSet}, {@code NavigableSet}, or {@code Collection} of a class, or of an array of a non-primitive
 * scalar, as the reflective scanner computes it), one {@link FilePartDescriptor} per named file part,
 * a {@link BodyDescriptor} when the operation binds a body, and the resolved annotations (empty for
 * an operation without a fixture). Captured schemas are carried by reference.
 */
public final class MetadataPublications {

    /** The mount path of {@link #mount()}. */
    public static final String MOUNT_PATH = "/api/meta/*";

    /** The application name of {@link #mount()}, which also names its document. */
    public static final String APPLICATION = "meta";

    private final Publications.Built source;
    private final Map<String, OperationEdits> edits = new LinkedHashMap<>();

    private MetadataPublications(Publications.Built source) {
        this.source = Objects.requireNonNull(source, "source");
        for (OperationPublication operation : source.publication().operations()) {
            if (operation.detail() == null) {
                throw new IllegalStateException("operation '" + operation.operationId() + "' carries no detail");
            }
            edits.put(operation.operationId(), new OperationEdits(operation));
        }
    }

    /**
     * Starts a synthetic mount {@value #MOUNT_PATH} of application {@value #APPLICATION}, declared by
     * {@link UnitDocumentedApi}.
     *
     * @return a new mount builder
     */
    public static Publications mount() {
        return Publications.mount(MOUNT_PATH).application(APPLICATION, UnitDocumentedApi.class);
    }

    /**
     * Starts rewriting a built publication.
     *
     * @param built the publication built by {@link Publications}
     * @return a new rewriter; the built publication is not changed
     */
    public static MetadataPublications from(Publications.Built built) {
        return new MetadataPublications(built);
    }

    /**
     * Annotates an operation from a fixture method, found by name among the class's declared methods,
     * else among its public methods; the name must identify exactly one method.
     *
     * @param operationId the operation id
     * @param fixtureClass the fixture class whose resolved annotations become the class annotations
     * @param methodName the fixture method's name
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown, the method is not found or is
     *     ambiguous, or its parameters do not match the operation's bindings
     */
    public MetadataPublications annotate(String operationId, Class<?> fixtureClass, String methodName) {
        Objects.requireNonNull(fixtureClass, "fixtureClass");
        Objects.requireNonNull(methodName, "methodName");
        OperationEdits operation = operation(operationId);
        operation.fixtureClass = fixtureClass;
        operation.fixtureMethod = method(fixtureClass, methodName);
        return this;
    }

    /**
     * Sets the requiredness of the one parameter binding or composite field with the given location
     * and name.
     *
     * @param operationId the operation id
     * @param location the binding's location
     * @param name the binding's name
     * @param requiredness the requiredness
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or not exactly one binding matches
     */
    public MetadataPublications requiredness(
            String operationId, ParamLocation location, String name, Requiredness requiredness) {
        OperationEdits operation = operation(operationId);
        operation.requiredness.put(operation.indexOf(location, name), Objects.requireNonNull(requiredness));
        return this;
    }

    /**
     * Flags the one binding with the given location and name hidden.
     *
     * @param operationId the operation id
     * @param location the binding's location
     * @param name the binding's name
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or not exactly one binding matches
     */
    public MetadataPublications hidden(String operationId, ParamLocation location, String name) {
        OperationEdits operation = operation(operationId);
        operation.hidden.add(operation.indexOf(location, name));
        return this;
    }

    /**
     * Flags the operation's body binding hidden.
     *
     * @param operationId the operation id
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public MetadataPublications hiddenBody(String operationId) {
        OperationEdits operation = operation(operationId);
        operation.hidden.add(operation.bodyIndex());
        return this;
    }

    /**
     * Sets the Java type of the one binding with the given location and name, overriding the
     * fixture's.
     *
     * @param operationId the operation id
     * @param location the binding's location
     * @param name the binding's name
     * @param type the type, for example a {@code List<String>} parameterized type
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or not exactly one binding matches
     */
    public MetadataPublications type(String operationId, ParamLocation location, String name, Type type) {
        OperationEdits operation = operation(operationId);
        operation.types.put(operation.indexOf(location, name), Objects.requireNonNull(type, "type"));
        return this;
    }

    /**
     * Sets the Java type of the operation's body binding, overriding the fixture's; {@link
     * Publications} alone sets {@code Object.class}.
     *
     * @param operationId the operation id
     * @param type the body type
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public MetadataPublications bodyType(String operationId, Type type) {
        OperationEdits operation = operation(operationId);
        operation.types.put(operation.bodyIndex(), Objects.requireNonNull(type, "type"));
        return this;
    }

    /**
     * Builds the publication as the sink receives it, every operation's detail carrying its stub
     * descriptor. Hand it to the documentation package's helper, which takes the descriptor facts
     * through the sink and detaches it.
     *
     * @return the publication with descriptors attached
     * @throws IllegalStateException if an annotated operation's fixture method does not match its
     *     bindings
     */
    public MountPublication build() {
        MountPublication mount = source.publication();
        List<OperationPublication> operations = new ArrayList<>();
        for (OperationPublication operation : mount.operations()) {
            OperationEdits operationEdits = edits.get(operation.operationId());
            operations.add(operationEdits.rewrite(
                    source.consumes().getOrDefault(operation.operationId(), List.of()),
                    source.namedFileParts().getOrDefault(operation.operationId(), List.of())));
        }
        return new MountPublication(
                mount.mountPath(),
                mount.mountId(),
                mount.applicationName(),
                mount.declaringType(),
                mount.strategyId(),
                operations);
    }

    private OperationEdits operation(String operationId) {
        OperationEdits operation = edits.get(Objects.requireNonNull(operationId, "operationId"));
        if (operation == null) {
            throw new IllegalStateException(
                    "the publication has no operation '" + operationId + "'; it has " + edits.keySet());
        }
        return operation;
    }

    private static Method method(Class<?> fixtureClass, String methodName) {
        List<Method> declared = Arrays.stream(fixtureClass.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName) && !method.isBridge() && !method.isSynthetic())
                .toList();
        List<Method> candidates = declared.isEmpty()
                ? Arrays.stream(fixtureClass.getMethods())
                        .filter(method -> method.getName().equals(methodName) && !method.isBridge())
                        .toList()
                : declared;
        if (candidates.size() != 1) {
            throw new IllegalStateException(fixtureClass.getName() + " has " + candidates.size() + " methods named '"
                    + methodName + "'; a fixture method name must be unique");
        }
        return candidates.get(0);
    }

    /**
     * The element type of a multi-value parameter type, as the reflective scanner computes it, or
     * {@code null} for a scalar.
     */
    private static @Nullable Class<?> elementType(Type type) {
        Class<?> raw = erasure(type);
        if (raw.isArray()) {
            Class<?> component = raw.getComponentType();
            return isScalarArrayComponent(component) ? component : null;
        }
        if (type instanceof ParameterizedType parameterized && isCollection(parameterized.getRawType())) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }

    private static boolean isCollection(Type raw) {
        return raw == List.class
                || raw == Set.class
                || raw == SortedSet.class
                || raw == NavigableSet.class
                || raw == Collection.class;
    }

    private static boolean isScalarArrayComponent(Class<?> component) {
        if (component.isPrimitive()) {
            return false;
        }
        return component == String.class
                || component == Integer.class
                || component == Long.class
                || component == Short.class
                || component == Byte.class
                || component == Double.class
                || component == Float.class
                || component == Boolean.class
                || component == Character.class
                || component.isEnum();
    }

    private static Class<?> erasure(Type type) {
        if (type instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof ParameterizedType parameterized) {
            return erasure(parameterized.getRawType());
        }
        if (type instanceof GenericArrayType array) {
            return Array.newInstance(erasure(array.getGenericComponentType()), 0)
                    .getClass();
        }
        return Object.class;
    }

    /** The JAX-RS parameter annotation's location and name, or {@code null} when it carries none. */
    private static @Nullable Map.Entry<ParamLocation, String> jaxRsBinding(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof QueryParam query) {
                return Map.entry(ParamLocation.QUERY, query.value());
            }
            if (annotation instanceof PathParam path) {
                return Map.entry(ParamLocation.PATH, path.value());
            }
            if (annotation instanceof HeaderParam header) {
                return Map.entry(ParamLocation.HEADER, header.value());
            }
            if (annotation instanceof CookieParam cookie) {
                return Map.entry(ParamLocation.COOKIE, cookie.value());
            }
            if (annotation instanceof FormParam form) {
                return Map.entry(ParamLocation.FORM, form.value());
            }
        }
        return null;
    }

    /** The pending edits of one operation. */
    private static final class OperationEdits {

        private final OperationPublication operation;
        private final OperationDetail detail;
        private final Set<Integer> hidden = new LinkedHashSet<>();
        private final Map<Integer, Type> types = new HashMap<>();
        private final Map<Integer, Requiredness> requiredness = new HashMap<>();
        private @Nullable Class<?> fixtureClass;
        private @Nullable Method fixtureMethod;

        private OperationEdits(OperationPublication operation) {
            this.operation = operation;
            this.detail = Objects.requireNonNull(operation.detail(), "detail");
        }

        private int indexOf(ParamLocation location, String name) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
            List<Integer> matches = new ArrayList<>();
            List<InputBinding> inputs = detail.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                InputBinding binding = inputs.get(i);
                if (binding.location() == location && name.equals(binding.name())) {
                    matches.add(i);
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("operation '" + operation.operationId() + "' has " + matches.size()
                        + " bindings at " + location + " '" + name + "'");
            }
            return matches.get(0);
        }

        private int bodyIndex() {
            List<InputBinding> inputs = detail.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                if (inputs.get(i).origin() == Origin.BODY) {
                    return i;
                }
            }
            throw new IllegalStateException("operation '" + operation.operationId() + "' has no body binding");
        }

        private OperationPublication rewrite(List<String> consumes, List<String> namedFileParts) {
            List<InputBinding> inputs = new ArrayList<>();
            List<InputBinding> original = detail.inputs();
            requireCoherentFixture(original);
            for (int i = 0; i < original.size(); i++) {
                InputBinding binding = original.get(i);
                Type type = binding.type();
                List<Annotation> annotations = binding.annotations();
                if (fixtureMethod != null && binding.origin() != Origin.COMPOSITE_FIELD) {
                    int index = Objects.requireNonNull(binding.methodParameterIndex(), "methodParameterIndex");
                    type = fixtureMethod.getGenericParameterTypes()[index];
                    annotations = List.of(AnnotationResolver.resolveParameterAnnotations(fixtureMethod, index));
                }
                inputs.add(new InputBinding(
                        binding.origin(),
                        binding.location(),
                        binding.name(),
                        types.getOrDefault(i, type),
                        binding.defaultValue(),
                        requiredness.getOrDefault(i, binding.requiredness()),
                        hidden.contains(i) || binding.hidden(),
                        binding.schemaEnforced(),
                        annotations,
                        binding.methodParameterIndex(),
                        binding.compositeType()));
            }
            StubOperationDescriptor descriptor = descriptor(inputs, consumes, namedFileParts);
            OperationDetail attached = new OperationDetail(
                    descriptor,
                    detail.profileId(),
                    detail.schemas(),
                    detail.gateInstalled(),
                    inputs,
                    detail.response());
            return new OperationPublication(
                    operation.operationId(),
                    operation.httpMethod(),
                    operation.jaxRsPathTemplate(),
                    operation.vertxRouteValue(),
                    operation.vertxRouteIsRegex(),
                    operation.effectivePolicy(),
                    operation.securityRequirementSets(),
                    operation.requiresAction(),
                    attached);
        }

        private void requireCoherentFixture(List<InputBinding> bindings) {
            if (fixtureMethod == null) {
                return;
            }
            Set<Integer> indexes = new LinkedHashSet<>();
            for (InputBinding binding : bindings) {
                if (binding.methodParameterIndex() != null) {
                    indexes.add(binding.methodParameterIndex());
                }
            }
            String subject = "operation '" + operation.operationId() + "' annotated from "
                    + fixtureMethod.getDeclaringClass().getName() + "#" + fixtureMethod.getName();
            if (fixtureMethod.getParameterCount() != indexes.size()) {
                throw new IllegalStateException(subject + ": the method has " + fixtureMethod.getParameterCount()
                        + " parameters but the operation binds " + indexes.size()
                        + " method parameters; list one fixture parameter per binding, in builder order");
            }
            for (InputBinding binding : bindings) {
                if (binding.origin() == Origin.COMPOSITE_FIELD) {
                    continue;
                }
                int index = Objects.requireNonNull(binding.methodParameterIndex(), "methodParameterIndex");
                Annotation[] annotations = AnnotationResolver.resolveParameterAnnotations(fixtureMethod, index);
                Map.Entry<ParamLocation, String> declared = jaxRsBinding(annotations);
                if (binding.origin() == Origin.BODY) {
                    if (declared != null) {
                        throw new IllegalStateException(subject + ": parameter " + index
                                + " stands for the body but carries a JAX-RS parameter annotation");
                    }
                } else if (declared == null
                        || declared.getKey() != binding.location()
                        || !declared.getValue().equals(binding.name())) {
                    throw new IllegalStateException(subject + ": parameter " + index + " must carry the JAX-RS "
                            + "parameter annotation of " + binding.location() + " '" + binding.name() + "'");
                }
            }
        }

        private StubOperationDescriptor descriptor(
                List<InputBinding> inputs, List<String> consumes, List<String> namedFileParts) {
            List<ParamDescriptor> parameters = new ArrayList<>();
            Optional<BodyDescriptor> body = Optional.empty();
            for (InputBinding binding : inputs) {
                Type type = binding.type();
                Class<?> raw = erasure(type);
                Type genericType = type instanceof Class<?> ? null : type;
                switch (binding.origin()) {
                    case PARAMETER ->
                        parameters.add(new ParamDescriptor(
                                binding.name(),
                                binding.location(),
                                raw,
                                elementType(type),
                                genericType,
                                binding.defaultValue(),
                                binding.annotations()));
                    case BODY -> body = Optional.of(new BodyDescriptor(raw, genericType, binding.annotations()));
                    case COMPOSITE_FIELD -> {
                        // A composite field is no method parameter of the descriptor.
                    }
                }
            }
            List<FilePartDescriptor> fileParts = new ArrayList<>();
            for (String part : namedFileParts) {
                fileParts.add(new FilePartDescriptor(part, List.of(), -1));
            }
            List<Annotation> methodAnnotations =
                    fixtureMethod == null ? List.of() : AnnotationResolver.resolveMethodAnnotations(fixtureMethod);
            List<Annotation> classAnnotations =
                    fixtureClass == null ? List.of() : AnnotationResolver.resolveClassAnnotations(fixtureClass);
            return new StubOperationDescriptor(
                    operation.operationId(),
                    operation.httpMethod(),
                    operation.jaxRsPathTemplate(),
                    consumes,
                    parameters,
                    fileParts,
                    body,
                    methodAnnotations,
                    classAnnotations);
        }
    }
}
