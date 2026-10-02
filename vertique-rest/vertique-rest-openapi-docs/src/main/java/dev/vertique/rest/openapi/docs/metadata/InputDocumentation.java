// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.metadata;

import static dev.vertique.rest.openapi.docs.metadata.AnnotationValues.first;
import static dev.vertique.rest.openapi.docs.metadata.AnnotationValues.isSet;
import static dev.vertique.rest.openapi.docs.metadata.AnnotationValues.setOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the documentation a document publishes on its Parameter Objects and request bodies from the
 * inputs' Swagger annotations.
 *
 * <p>A Parameter Object takes its documentation from the first {@link Parameter} among its binding's
 * annotations: {@code deprecated} ({@code true} only), {@code example}, and {@code examples}. Its own
 * {@link Parameter#schema()} fills what the {@link Parameter} leaves unset: its {@code description}
 * when the parameter's description is blank, its {@code example} when the {@link Parameter} sets
 * neither {@code example} nor {@code examples}, and {@code deprecated} when it is {@code true}. Named
 * examples win over an example. The schema's {@code title} and {@code externalDocs}, and the members
 * of an array schema's element schema, are never published.
 *
 * <p>A request body takes its documentation from one {@link RequestBody}: the first that sets any
 * member of the body binding's annotation, the operation method's annotation, and the first {@link
 * Operation}'s {@link Operation#requestBody()}; their members are never merged. Its {@code
 * description} is published when set, else the description of the first {@link Content} schema, in
 * declaration order, that sets one. Each {@link Content} entry applies to its media type, or to every
 * media type of the body when its media type is empty; for each media type of the body, the first
 * applying entry that declares named examples or whose schema sets an example supplies the Media Type
 * Object's {@code examples}, else its {@code example}. Entries never add a media type the body does
 * not publish. No {@code example}, {@code examples}, or {@code deprecated} is written on the request
 * body itself, and a body's own {@code deprecated} is published nowhere.
 *
 * <p>Example values and named examples follow {@link Examples}. A reference in an example fails
 * publication: the document declares no reusable examples for it to name. A reference in a {@link
 * Parameter} or a {@link RequestBody} is refused by {@code MetadataAgreement} before the
 * documentation is read. Failures name the operation, the input, and the attribute, never a value.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class InputDocumentation {

    /** How messages name a request body. */
    public static final String REQUEST_BODY = "request body";

    private InputDocumentation() {}

    /**
     * Returns how messages name an input that is not the request body.
     *
     * @param binding the input's binding
     * @return {@code <location> parameter <name>}, for example {@code query parameter q}, or {@code
     *     form field <name>} for a form binding
     */
    public static String phrase(InputBinding binding) {
        if (binding.location() == ParamLocation.FORM) {
            return "form field " + binding.name();
        }
        return binding.location().name().toLowerCase(Locale.ROOT) + " parameter " + binding.name();
    }

    /**
     * Reads the documentation of one Parameter Object.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param binding the parameter's binding
     * @param description the parameter's own description, or {@code null} when it has none
     * @return the documentation
     * @throws RestConfigurationException when one of the {@link Parameter}'s examples sets a reference
     *     or is malformed
     */
    public static ParameterDocumentation parameter(
            String subject, String operationId, InputBinding binding, @Nullable String description) {
        Parameter parameter = first(binding.annotations(), Parameter.class);
        if (parameter == null) {
            return new ParameterDocumentation(description, false, null, null);
        }
        ObjectNode examples = Examples.render(
                subject, operationId, "@Parameter.examples on " + phrase(binding), parameter.examples());
        Schema schema = parameter.schema();
        JsonNode example = null;
        if (examples == null) {
            if (isSet(parameter.example())) {
                example = Examples.value(parameter.example());
            } else if (isSet(schema.example())) {
                example = Examples.value(schema.example());
            }
        }
        String filled = description != null ? description : setOrNull(schema.description());
        return new ParameterDocumentation(filled, parameter.deprecated() || schema.deprecated(), example, examples);
    }

    /**
     * Selects the {@link RequestBody} that documents an operation's request body: the first that
     * sets any member of the body binding's annotation, the first among the method annotations, and
     * the {@link Operation#requestBody()} of the first {@link Operation} among them.
     *
     * @param bodyAnnotations the body binding's annotations, or an empty list for a form body
     * @param methodAnnotations the operation method's resolved annotations
     * @return the annotation, or {@code null} when none sets a member
     */
    @Nullable
    public static RequestBody requestBody(List<Annotation> bodyAnnotations, List<Annotation> methodAnnotations) {
        RequestBody onBody = first(bodyAnnotations, RequestBody.class);
        if (onBody != null && !isDefault(onBody)) {
            return onBody;
        }
        RequestBody onMethod = first(methodAnnotations, RequestBody.class);
        if (onMethod != null && !isDefault(onMethod)) {
            return onMethod;
        }
        Operation operation = first(methodAnnotations, Operation.class);
        if (operation != null && !isDefault(operation.requestBody())) {
            return operation.requestBody();
        }
        return null;
    }

    /**
     * Reads the documentation of a request body.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param requestBody the documenting annotation, or {@code null} when there is none
     * @param mediaTypes the media types the body publishes, in order
     * @return the documentation
     * @throws RestConfigurationException when one of the annotation's content entries' examples is
     *     malformed or sets a reference
     */
    public static BodyDocumentation body(
            String subject, String operationId, @Nullable RequestBody requestBody, List<String> mediaTypes) {
        if (requestBody == null) {
            return BodyDocumentation.NONE;
        }
        String description = bodyDescription(requestBody);
        String where = "@RequestBody.content.examples on " + REQUEST_BODY;
        Map<String, MediaTypeExamples> examples = new LinkedHashMap<>();
        for (Content content : requestBody.content()) {
            ObjectNode named = Examples.render(subject, operationId, where, content.examples());
            JsonNode example = named == null && isSet(content.schema().example())
                    ? Examples.value(content.schema().example())
                    : null;
            if (named == null && example == null) {
                continue;
            }
            for (String mediaType : mediaTypes) {
                if ((content.mediaType().isEmpty() || content.mediaType().equals(mediaType))
                        && !examples.containsKey(mediaType)) {
                    examples.put(mediaType, new MediaTypeExamples(example, named));
                }
            }
        }
        return new BodyDocumentation(description, examples);
    }

    /**
     * Checks the named examples of a form request body without publishing them: the document
     * publishes no examples for a form media type, but a blank or repeated name, an example with both
     * a value and an external value, and a reference still fail.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param requestBody the documenting annotation, or {@code null} when there is none
     * @throws RestConfigurationException when an example of one of the annotation's content entries
     *     is malformed or sets a reference
     */
    public static void checkFormBodyExamples(String subject, String operationId, @Nullable RequestBody requestBody) {
        if (requestBody == null) {
            return;
        }
        String where = "@RequestBody.content.examples on " + REQUEST_BODY;
        for (Content content : requestBody.content()) {
            Examples.render(subject, operationId, where, content.examples());
        }
    }

    /**
     * Checks the named examples of a form field without publishing them: the first {@link Parameter}
     * on the field is read as it is for a Parameter Object, and a blank or repeated name, an example
     * with both a value and an external value, and a reference fail.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param binding the form field's binding
     * @throws RestConfigurationException when one of the {@link Parameter}'s examples is malformed or
     *     sets a reference
     */
    public static void checkFormFieldExamples(String subject, String operationId, InputBinding binding) {
        Parameter parameter = first(binding.annotations(), Parameter.class);
        if (parameter != null) {
            Examples.render(subject, operationId, "@Parameter.examples on " + phrase(binding), parameter.examples());
        }
    }

    /**
     * Reads the description of a request body.
     *
     * @param requestBody the documenting annotation, or {@code null} when there is none
     * @return the annotation's description when set, else the description of the first content
     *     schema that sets one, else {@code null}
     */
    @Nullable
    public static String bodyDescription(@Nullable RequestBody requestBody) {
        if (requestBody == null) {
            return null;
        }
        if (isSet(requestBody.description())) {
            return requestBody.description();
        }
        for (Content content : requestBody.content()) {
            if (isSet(content.schema().description())) {
                return content.schema().description();
            }
        }
        return null;
    }

    /**
     * Tells whether an annotation member equals its declared default.
     *
     * @param annotation the annotation
     * @param member a member of the annotation's type
     * @return {@code true} when the member's value deeply equals its declared default
     */
    public static boolean isDefault(Annotation annotation, Method member) {
        try {
            return Objects.deepEquals(member.invoke(annotation), member.getDefaultValue());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read annotation member " + member.getName(), e);
        }
    }

    /** Tells whether every member of an annotation declares a default and equals it. */
    private static boolean isDefault(Annotation annotation) {
        for (Method member : annotation.annotationType().getDeclaredMethods()) {
            if (member.getDefaultValue() == null || !isDefault(annotation, member)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The documentation of one Parameter Object.
     *
     * @param description the description, or {@code null}
     * @param deprecated whether the parameter is deprecated
     * @param example the example value, or {@code null}
     * @param examples the named examples, or {@code null}
     */
    public record ParameterDocumentation(
            @Nullable String description,
            boolean deprecated,
            @Nullable JsonNode example,
            @Nullable ObjectNode examples) {}

    /**
     * The documentation of a request body.
     *
     * @param description the description, or {@code null}
     * @param examples the examples of each media type that has any, keyed by media type
     */
    public record BodyDocumentation(@Nullable String description, Map<String, MediaTypeExamples> examples) {

        /** The documentation of a request body no annotation documents. */
        public static final BodyDocumentation NONE = new BodyDocumentation(null, Map.of());

        /** Stores an unmodifiable copy of the examples, keeping their order. */
        public BodyDocumentation {
            examples = Collections.unmodifiableMap(new LinkedHashMap<>(examples));
        }
    }

    /**
     * The examples of one Media Type Object: named examples, or else one example value.
     *
     * @param example the example value, or {@code null}
     * @param examples the named examples, or {@code null}
     */
    public record MediaTypeExamples(
            @Nullable JsonNode example, @Nullable ObjectNode examples) {

        /**
         * Writes the examples after the Media Type Object's schema: {@code examples} when there are
         * named examples, else {@code example}, each a copy.
         *
         * @param mediaType the Media Type Object
         */
        public void write(ObjectNode mediaType) {
            if (examples != null) {
                mediaType.set("examples", examples.deepCopy());
            } else if (example != null) {
                mediaType.set("example", example.deepCopy());
            }
        }
    }
}
