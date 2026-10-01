// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Classifies the response of one operation's resource method into the kind of response a document
 * can describe.
 */
final class ResponseInference {

    /** The kind of response a resource method produces. */
    enum Row {
        /** The response carries no content. */
        NO_CONTENT,
        /** The response is a JSON entity serialized from the method's return type. */
        JSON_ENTITY,
        /** The response is raw text. */
        RAW_TEXT,
        /** The response is decided at runtime and cannot be described statically. */
        RUNTIME
    }

    /**
     * The classification of one response.
     *
     * @param row the kind of response
     * @param status the published status key, or {@code default} when it is not known
     * @param mediaTypes the media types the response publishes, in published order
     * @param outputType the type the response body is described from, or {@code null} when none
     */
    record Inference(
            Row row,
            String status,
            List<String> mediaTypes,
            @Nullable Type outputType) {

        /**
         * Returns whether the response can be described statically.
         *
         * @return {@code true} for a JSON entity or raw text response
         */
        boolean inferable() {
            return row == Row.JSON_ENTITY || row == Row.RAW_TEXT;
        }
    }

    private static final Inference NO_CONTENT = new Inference(Row.NO_CONTENT, "204", List.of(), null);
    private static final Inference RUNTIME = new Inference(Row.RUNTIME, ResponseStatuses.DEFAULT, List.of(), null);
    private static final String DEFAULT_MEDIA_TYPE = "application/json";
    private static final String EVENT_STREAM = "text/event-stream";

    private ResponseInference() {}

    /**
     * Returns the media types a response is published under: the method's declared ones, or {@code
     * application/json} when it declares none.
     *
     * @param shape the response facts of the method
     * @return the declared media types in declaration order, or {@code application/json} alone
     */
    static List<String> producedMediaTypes(ResponseShape shape) {
        return shape.produces().isEmpty() ? List.of(DEFAULT_MEDIA_TYPE) : shape.produces();
    }

    /**
     * Classifies the response of a resource method.
     *
     * <p>A method returning {@code void}, {@code Void}, or {@code Future<Void>} produces no content.
     * Otherwise every type variable of the return type is resolved against the generic supertypes
     * of the resource class, one level of {@code io.vertx.core.Future} itself is unwrapped (a subtype
     * of {@code Future} is decided at runtime), and the resulting type is classified: a type still
     * holding a type variable or wildcard, a type the runtime turns into a response itself ({@code
     * Response}, {@code CompletionStage}, {@code Optional}, {@code ReadStream}, {@code Buffer},
     * {@code byte[]}, a {@code Future} or subtype of it left inside the unwrapped one, or a type
     * with a registered response producer)
     * is decided at runtime; a {@code String} is raw text under every declared media type; an
     * event stream is decided at runtime; any other type is a JSON entity under the JSON-compatible
     * declared media types, and is decided at runtime when none is declared.
     *
     * @param shape the response facts of the method
     * @param bindings the registered response producer bindings
     * @return the classification
     */
    static Inference classify(ResponseShape shape, Set<ResponseProducerBinding<?>> bindings) {
        if (shape.returnsVoid()) {
            return NO_CONTENT;
        }
        Type type = resolve(shape.genericReturnType(), typeArguments(shape.resourceClass()));
        if (rawClass(type) == Future.class) {
            if (!(type instanceof ParameterizedType future)) {
                return RUNTIME;
            }
            type = future.getActualTypeArguments()[0];
        } else if (rawClass(type) != null && Future.class.isAssignableFrom(rawClass(type))) {
            return RUNTIME;
        }
        if (isOpen(type)) {
            return RUNTIME;
        }
        Class<?> raw = rawClass(type);
        if (raw == null || isRuntimeDecided(raw) || isProducerBound(raw, bindings)) {
            return RUNTIME;
        }
        List<String> produces = producedMediaTypes(shape);
        if (raw == String.class) {
            return new Inference(Row.RAW_TEXT, "200", produces, null);
        }
        if (produces.stream().anyMatch(mediaType -> mediaType.startsWith(EVENT_STREAM))) {
            return RUNTIME;
        }
        List<String> json = produces.stream()
                .filter(mediaType -> mediaType.contains("json"))
                .toList();
        if (json.isEmpty()) {
            return RUNTIME;
        }
        return new Inference(Row.JSON_ENTITY, "200", json, type);
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    /** Whether the runtime turns a value of the class into a response without an entity encoder. */
    private static boolean isRuntimeDecided(Class<?> raw) {
        return Response.class.isAssignableFrom(raw)
                || Future.class.isAssignableFrom(raw)
                || CompletionStage.class.isAssignableFrom(raw)
                || raw == Optional.class
                || ReadStream.class.isAssignableFrom(raw)
                || Buffer.class.isAssignableFrom(raw)
                || raw == byte[].class;
    }

    /**
     * Whether a response producer is registered for the class, a superclass, or an interface of it,
     * walked in the order the response pipeline looks a producer up.
     */
    private static boolean isProducerBound(Class<?> raw, Set<ResponseProducerBinding<?>> bindings) {
        if (bindings.isEmpty()) {
            return false;
        }
        Set<Class<?>> bound = new HashSet<>();
        for (ResponseProducerBinding<?> binding : bindings) {
            bound.add(binding.type());
        }
        for (Class<?> current = raw; current != null; current = current.getSuperclass()) {
            if (bound.contains(current)) {
                return true;
            }
        }
        for (Class<?> iface : TypeResolver.getAllInterfaces(raw)) {
            if (bound.contains(iface)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the type holds a type variable or a wildcard at any depth. */
    private static boolean isOpen(Type type) {
        if (type instanceof TypeVariable<?> || type instanceof WildcardType) {
            return true;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type owner = parameterized.getOwnerType();
            if (owner != null && isOpen(owner)) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (isOpen(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType array) {
            return isOpen(array.getGenericComponentType());
        }
        return false;
    }

    /** Returns the raw class of a type, or {@code null} for a type variable or wildcard. */
    private static @Nullable Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
            return c;
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = rawClass(array.getGenericComponentType());
            return component == null ? null : component.arrayType();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Type variable resolution
    // ---------------------------------------------------------------------------------------------

    /**
     * Maps every type variable a generic supertype of the class binds, through its superclass chain
     * and its interfaces, to the type the binding resolves to from the class.
     */
    private static Map<TypeVariable<?>, Type> typeArguments(Class<?> resourceClass) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collect(resourceClass, bindings, new HashSet<>());
        return bindings;
    }

    private static void collect(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return;
        }
        List<Type> supertypes = new ArrayList<>();
        Type superclass = type.getGenericSuperclass();
        if (superclass != null) {
            supertypes.add(superclass);
        }
        supertypes.addAll(Arrays.asList(type.getGenericInterfaces()));
        for (Type supertype : supertypes) {
            Class<?> raw = rawClass(supertype);
            if (raw == null) {
                continue;
            }
            if (supertype instanceof ParameterizedType parameterized) {
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    bindings.putIfAbsent(variables[i], resolve(arguments[i], bindings));
                }
            }
            collect(raw, bindings, visited);
        }
    }

    /**
     * Substitutes every bound type variable in the type, returning the type itself when nothing
     * changes.
     */
    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            return bindings.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type owner = parameterized.getOwnerType();
            Type resolvedOwner = owner == null ? null : resolve(owner, bindings);
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[arguments.length];
            boolean changed = resolvedOwner != owner;
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = resolve(arguments[i], bindings);
                changed |= resolved[i] != arguments[i];
            }
            return changed ? new ResolvedParameterizedType(raw, resolved, resolvedOwner) : type;
        }
        if (type instanceof GenericArrayType array) {
            Type component = array.getGenericComponentType();
            Type resolved = resolve(component, bindings);
            if (resolved == component) {
                return type;
            }
            if (resolved instanceof Class<?> c) {
                return Array.newInstance(c, 0).getClass();
            }
            return new ResolvedGenericArrayType(resolved);
        }
        return type;
    }

    /**
     * A generic array type whose component was substituted, equal to the JDK's representation of
     * the same type in both directions.
     */
    private static final class ResolvedGenericArrayType implements GenericArrayType {

        private final Type componentType;

        ResolvedGenericArrayType(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GenericArrayType that && Objects.equals(componentType, that.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(componentType);
        }

        @Override
        public String getTypeName() {
            return toString();
        }

        @Override
        public String toString() {
            return componentType.getTypeName() + "[]";
        }
    }
}
