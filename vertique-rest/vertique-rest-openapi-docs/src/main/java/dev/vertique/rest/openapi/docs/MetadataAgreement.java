// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.InputDocumentation.first;
import static dev.vertique.rest.openapi.docs.InputDocumentation.isDefault;
import static dev.vertique.rest.openapi.docs.OperationMetadata.isSet;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks, for one document's assembly, that the Swagger annotations of each published operation agree
 * with how the runtime binds it, and collects the warnings about annotation members the document does
 * not publish.
 *
 * <p>Documentation metadata never changes a published input name, location, shape, requiredness,
 * or validation keyword. An annotation that contradicts a fact the runtime owns fails publication;
 * each failure names the application, the mount, the operation, and the attribute with the input it
 * is declared on (for example {@code @Parameter.in on query parameter q}), never an annotation value:
 *
 * <ul>
 *   <li>a non-blank {@link Operation#operationId()} of the first {@link Operation} that differs from
 *       the runtime operation id;
 *   <li>on the request body, a non-blank {@link Content#mediaType()} of {@link RequestBody#content()}
 *       that is not among the media types the body publishes; a {@link Schema#implementation()} in a
 *       content schema other than the bound type's erasure, a primitive type and its wrapper being
 *       equal (on a form body, any implementation); and {@link RequestBody#required()} {@code true}
 *       while the published request body is not required (a form body never is);
 *   <li>on a parameter or form field, a {@link Parameter#content()}, which the runtime never binds; a
 *       non-blank {@link Parameter#name()} other than the binding name; an {@link Parameter#in()}
 *       other than {@link ParameterIn#DEFAULT} and the binding location (any on a form field); {@link
 *       Parameter#required()} {@code true} on a binding the runtime certainly binds when the value is
 *       missing; a {@link Schema#implementation()} in {@link Parameter#schema()} other than the bound
 *       type's erasure; and an {@link ArraySchema#schema()} implementation in {@link Parameter#array()}
 *       other than the element type's erasure of a collection or array input, or any on another
 *       input. The element type is the one the descriptor recorded, else the bound type's type
 *       argument or array component; a collection whose element type is not known has nothing to
 *       contradict.
 * </ul>
 *
 * <p>{@link Parameter#required()} {@code true} on a binding whose requiredness the runtime leaves
 * unknown is not published and is named in one warning per input. Every other non-default member of
 * a {@link Schema} in {@link Parameter#schema()} or a request-body content schema, of an {@link
 * ArraySchema} in {@link Parameter#array()} or a request-body content entry, and of that array's
 * element {@link Schema}, is not published, because each input's canonical schema stays
 * authoritative; one warning per operation names every such member as {@code <annotation path> on
 * <input>}, for example {@code @Parameter.schema.maxLength on query parameter q}. The documentation
 * members {@code description}, {@code title}, {@code example}, {@code deprecated}, and {@code
 * externalDocs}, and a compared {@code implementation}, are exempt. A {@link Parameter} on a body
 * binding is neither read nor checked.
 *
 * <p>Warnings are held in a {@link PendingWarnings} until the document is written; each starts with
 * the document's configuration path, names the mount and the operation, and carries no annotation
 * value or schema text.
 */
final class MetadataAgreement {

    /** The documentation members of a schema annotation, which never warn. */
    private static final Set<String> DOCUMENTATION_MEMBERS =
            Set.of("description", "title", "example", "deprecated", "externalDocs");

    /** The schema member compared with the bound type. */
    private static final String IMPLEMENTATION = "implementation";

    /** The array-schema member that holds the element schema. */
    private static final String ELEMENT_SCHEMA = "schema";

    /** The warning kind of ignored schema members, followed by the operation id. */
    private static final String IGNORED_KIND = "metadata.ignored-schema-members:";

    /** The warning kind of a requirement of unknown requiredness, followed by operation and input. */
    private static final String UNKNOWN_KIND = "metadata.unknown-requiredness:";

    /** The members of {@link Schema}, sorted by name. */
    private static final List<Method> SCHEMA_MEMBERS = members(Schema.class);

    /** The members of {@link ArraySchema}, sorted by name. */
    private static final List<Method> ARRAY_MEMBERS = members(ArraySchema.class);

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            void.class, Void.class);

    private final String subject;
    private final String warningPrefix;
    private final String mountPath;
    private final PendingWarnings warnings;

    /**
     * Creates the checks of one document's assembly.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param documentName the document's application name
     * @param mountPath the mount path, as registered
     * @param warnings the warnings the assembly holds back until the document is written
     */
    MetadataAgreement(String subject, String documentName, String mountPath, PendingWarnings warnings) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.warningPrefix = "apidocs.documents." + Objects.requireNonNull(documentName, "documentName");
        this.mountPath = Objects.requireNonNull(mountPath, "mountPath");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /**
     * Starts the checks of one operation, checking its {@link Operation#operationId()} first.
     *
     * @param operationId the runtime operation id
     * @param facts the operation's descriptor facts
     * @return the checks of the operation's inputs
     * @throws RestConfigurationException when the first {@link Operation} names another operation id
     */
    OperationAgreement operation(String operationId, OperationFacts facts) {
        Operation operation = first(facts.methodAnnotations(), Operation.class);
        if (operation != null
                && isSet(operation.operationId())
                && !operation.operationId().equals(operationId)) {
            throw new RestConfigurationException(subject + ": operation '" + operationId
                    + "' declares @Operation.operationId, which differs from the runtime operation id; documentation"
                    + " metadata cannot change it, so remove the attribute or make it agree");
        }
        return new OperationAgreement(operationId, facts);
    }

    /** Builds the failure of an attribute that contradicts a fact the runtime owns. */
    private RestConfigurationException contradiction(String operationId, String attribute, String fact) {
        return new RestConfigurationException(subject + ": operation '" + operationId + "' declares " + attribute
                + ", which contradicts how the runtime binds it (" + fact + "); documentation metadata cannot change"
                + " it, so remove the attribute or make it agree");
    }

    /** The checks and the collected warnings of one operation's inputs. */
    final class OperationAgreement {

        private final String operationId;
        private final OperationFacts facts;
        private final List<String> ignored = new ArrayList<>();
        private final List<UnknownRequirement> unknown = new ArrayList<>();

        private OperationAgreement(String operationId, OperationFacts facts) {
            this.operationId = operationId;
            this.facts = facts;
        }

        /**
         * Checks the annotation that documents the request body, then collects its ignored members:
         * its reference, its content media types, its content schemas' implementations, and its
         * requiredness, in that order.
         *
         * @param requestBody the documenting annotation, or {@code null} when there is none
         * @param mediaTypes the media types the body publishes
         * @param boundType the bound body type, or {@code null} for a form body
         * @param required whether the published request body is required
         * @throws RestConfigurationException when the annotation sets a reference or contradicts the
         *     runtime
         */
        void requestBody(
                @Nullable RequestBody requestBody,
                List<String> mediaTypes,
                @Nullable Type boundType,
                boolean required) {
            if (requestBody == null) {
                return;
            }
            String input = InputDocumentation.REQUEST_BODY;
            if (isSet(requestBody.ref())) {
                throw InputDocumentation.unresolvedReference(subject, operationId, "@RequestBody.ref on " + input);
            }
            for (Content content : requestBody.content()) {
                if (isSet(content.mediaType()) && !mediaTypes.contains(content.mediaType())) {
                    throw contradiction(
                            operationId, "@RequestBody.content.mediaType on " + input, "the media types it consumes");
                }
            }
            for (Content content : requestBody.content()) {
                Class<?> implementation = content.schema().implementation();
                if (implementation != Void.class
                        && (boundType == null || !sameType(implementation, erasure(boundType)))) {
                    throw contradiction(
                            operationId, "@RequestBody.content.schema.implementation on " + input, "its bound type");
                }
            }
            if (requestBody.required() && !required) {
                throw contradiction(operationId, "@RequestBody.required on " + input, "it accepts an absent body");
            }
            for (Content content : requestBody.content()) {
                collectSchema(content.schema(), "@RequestBody.content.schema", input, true);
                collectArray(content.array(), "@RequestBody.content.array", input, false);
            }
        }

        /**
         * Checks the {@link Parameter} of a parameter or form field, then collects its ignored members
         * and a requirement of unknown requiredness: its reference, content, name, location,
         * requiredness, schema implementation, and array element implementation, in that order.
         *
         * @param binding the parameter's or form field's binding
         * @throws RestConfigurationException when the annotation sets a reference or contradicts the
         *     runtime
         */
        void parameter(InputBinding binding) {
            Parameter parameter = first(binding.annotations(), Parameter.class);
            if (parameter == null) {
                return;
            }
            String input = InputDocumentation.phrase(binding);
            if (isSet(parameter.ref())) {
                throw InputDocumentation.unresolvedReference(subject, operationId, "@Parameter.ref on " + input);
            }
            if (parameter.content().length > 0) {
                throw contradiction(operationId, "@Parameter.content on " + input, "a parameter's content");
            }
            if (isSet(parameter.name()) && !parameter.name().equals(binding.name())) {
                throw contradiction(operationId, "@Parameter.name on " + input, "its name");
            }
            if (parameter.in() != ParameterIn.DEFAULT
                    && !parameter.in().name().equals(binding.location().name())) {
                throw contradiction(operationId, "@Parameter.in on " + input, "its location");
            }
            if (parameter.required()) {
                if (binding.requiredness() == InputBinding.Requiredness.NOT_REQUIRED) {
                    throw contradiction(operationId, "@Parameter.required on " + input, "it accepts a missing value");
                }
                if (binding.requiredness() == InputBinding.Requiredness.UNKNOWN) {
                    unknown.add(new UnknownRequirement(
                            binding.location().name().toLowerCase(Locale.ROOT), binding.name(), input));
                }
            }
            Class<?> implementation = parameter.schema().implementation();
            if (implementation != Void.class && !sameType(implementation, erasure(binding.type()))) {
                throw contradiction(operationId, "@Parameter.schema.implementation on " + input, "its bound type");
            }
            Class<?> elementImplementation = parameter.array().schema().implementation();
            if (elementImplementation != Void.class) {
                Class<?> element = elementType(binding);
                boolean mismatch = element == null
                        ? !isMultiValued(erasure(binding.type()))
                        : !sameType(elementImplementation, element);
                if (mismatch) {
                    throw contradiction(
                            operationId, "@Parameter.array.schema.implementation on " + input, "its element type");
                }
            }
            collectSchema(parameter.schema(), "@Parameter.schema", input, true);
            collectArray(parameter.array(), "@Parameter.array", input, true);
        }

        /**
         * Holds back the operation's warnings once its inputs are checked: the ignored members first,
         * in one warning, then one warning per requirement of unknown requiredness, in input order.
         */
        void finish() {
            String operation = warningPrefix + ": operation '" + operationId + "' at mount '" + mountPath + "'";
            if (!ignored.isEmpty()) {
                warnings.add(
                        IGNORED_KIND + operationId,
                        operation + " declares schema members the document does not publish, because each input's"
                                + " canonical schema stays authoritative: " + String.join(", ", ignored));
            }
            for (UnknownRequirement requirement : unknown) {
                warnings.add(
                        UNKNOWN_KIND + operationId + ":" + requirement.in() + ":" + requirement.name(),
                        operation + " declares @Parameter.required on " + requirement.input()
                                + ", but whether the runtime"
                                + " rejects a missing value cannot be determined, so the document does not mark it"
                                + " required");
            }
        }

        /**
         * Returns the element type of a binding: the one the descriptor recorded for a method
         * parameter, else its type argument or array component, or {@code null} when none is known.
         */
        @Nullable
        private Class<?> elementType(InputBinding binding) {
            if (binding.origin() == InputBinding.Origin.PARAMETER) {
                Class<?> recorded = facts.elementTypes().get(new InputKey(binding.location(), binding.name()));
                if (recorded != null) {
                    return recorded;
                }
            }
            Type type = binding.type();
            if (type instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw
                    && Collection.class.isAssignableFrom(raw)
                    && parameterized.getActualTypeArguments().length == 1) {
                Type argument = parameterized.getActualTypeArguments()[0];
                return argument instanceof WildcardType ? null : erasure(argument);
            }
            if (type instanceof GenericArrayType array) {
                return erasure(array.getGenericComponentType());
            }
            if (type instanceof Class<?> raw && raw.isArray()) {
                return raw.getComponentType();
            }
            return null;
        }

        /** Collects the non-default members of a schema annotation that are not published. */
        private void collectSchema(Schema schema, String path, String input, boolean implementationCompared) {
            for (Method member : SCHEMA_MEMBERS) {
                String name = member.getName();
                if (DOCUMENTATION_MEMBERS.contains(name) || (implementationCompared && name.equals(IMPLEMENTATION))) {
                    continue;
                }
                if (!isDefault(schema, member)) {
                    ignored.add(path + "." + name + " on " + input);
                }
            }
        }

        /**
         * Collects the non-default members of an array-schema annotation, then those of its element
         * schema, that are not published.
         */
        private void collectArray(ArraySchema array, String path, String input, boolean implementationCompared) {
            for (Method member : ARRAY_MEMBERS) {
                if (!member.getName().equals(ELEMENT_SCHEMA) && !isDefault(array, member)) {
                    ignored.add(path + "." + member.getName() + " on " + input);
                }
            }
            collectSchema(array.schema(), path + "." + ELEMENT_SCHEMA, input, implementationCompared);
        }
    }

    /**
     * A requirement declared on an input whose requiredness the runtime leaves unknown.
     *
     * @param in the input's lowercase location
     * @param name the input's name
     * @param input how messages name the input
     */
    private record UnknownRequirement(String in, String name, String input) {}

    /** Returns the members of an annotation type that declare a default, sorted by name. */
    private static List<Method> members(Class<? extends Annotation> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(member -> member.getDefaultValue() != null)
                .sorted(Comparator.comparing(Method::getName))
                .toList();
    }

    /** Tells whether two types are the same, a primitive type and its wrapper being equal. */
    private static boolean sameType(Class<?> declared, Class<?> bound) {
        return WRAPPERS.getOrDefault(declared, declared) == WRAPPERS.getOrDefault(bound, bound);
    }

    /** Tells whether a bound type holds several values: a collection or an array. */
    private static boolean isMultiValued(Class<?> type) {
        return type.isArray() || Collection.class.isAssignableFrom(type);
    }

    /** Returns the erasure of a type; a type variable or wildcard erases to {@link Object}. */
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
}
