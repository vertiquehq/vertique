// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.swagger.v3.oas.annotations.Parameter;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the Operation Object of one operation from its binding inventory: its Parameter Objects
 * and its request body.
 *
 * <p>Assembly runs in two phases over the whole document. {@link #check} first plans an operation and
 * checks every captured schema it will publish: the request body first, then the parameters in
 * published order, then the form fields. Only when every operation of the document is checked does
 * {@link Plan#publish} write the Operation Object, publishing each checked schema exactly once.
 *
 * <p>Before anything else, an operation that hides a path parameter fails publication (see {@link
 * HiddenInputs}). Every input the inventory flags hidden is then left out: it publishes nothing, and
 * no later step, the checks included, reads it or its captured schema. A hidden body adds no request
 * body, and a form whose every binding is hidden adds none either.
 *
 * <p>The visible inputs are taken from the inventory as follows:
 *
 * <ul>
 *   <li>The binding whose origin is the body becomes the request body: one media type per consumed
 *       type ({@code application/json} when none is declared), each referencing the component of the
 *       captured body, or holding the empty schema when no body schema was captured. The body is
 *       {@code required} exactly when a validation gate was installed and the captured body schema,
 *       evaluated with the gate's options, rejects {@code null}; the body binding's own requiredness
 *       is never read.
 *   <li>Every form binding, method parameter or composite field, becomes a property of the form
 *       request body, in inventory order: one media type per consumed type, or, when none is
 *       declared, {@code multipart/form-data} when a visible form binding is a named file part and
 *       else {@code application/x-www-form-urlencoded}; a hidden file part never chooses it. A
 *       named file part's property is the empty schema. A form body never carries {@code
 *       required}. When an operation binds a body, its form bindings add no request body.
 *   <li>Every other binding becomes a Parameter Object: method parameters first, then composite
 *       fields, each in inventory order. A parameter carries {@code required: true} exactly when its
 *       binding is certainly required, and the description of its {@link Parameter} annotation when
 *       that is not blank.
 * </ul>
 *
 * <p>A captured request body is published only once its redaction manifest is verified against the
 * capture (see {@link ManifestVerifier}); the document's own copy is then checked for refused
 * constructs, and the reserved-name assertions the manifest lists are removed from it (see {@link
 * ReservedNameRedaction}) before it is relocated into a component. A method parameter or form field
 * with a captured schema may not hold {@code propertyNames} (see {@link ParameterPropertyNames}); it
 * publishes that schema unchanged, inline or as a component (see {@link SchemaEmbedder}). A
 * composite field, or an input with no captured schema, is unenforced: it publishes {@code
 * {"default": "<raw text>"}} when it declares a default value, else the empty schema; nothing is
 * derived from its Java type or annotations. Two visible inputs of one operation with the same name
 * and location fail publication.
 */
final class InputAssembler {

    /** The media type of a request body when the operation declares none. */
    static final String DEFAULT_BODY_MEDIA_TYPE = "application/json";

    /** The media type of a form body with a named file part when the operation declares none. */
    static final String MULTIPART = "multipart/form-data";

    /** The media type of a form body without a named file part when the operation declares none. */
    static final String URL_ENCODED = "application/x-www-form-urlencoded";

    /** The options the request-validation gate compiles its schemas with. */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The facts of an operation the documentation sink took none for. */
    private static final OperationFacts NO_FACTS = new OperationFacts(List.of(), List.of());

    private InputAssembler() {}

    /**
     * Plans the Operation Object of one operation and checks every captured schema it will publish.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param embedder the schema embedder of the document
     * @param operation the operation
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @param context the per-application inputs, naming the bound schema source
     * @param tally the disclosure tally of the document's assembly
     * @return the plan, to publish once every operation of the document is checked
     * @throws RestConfigurationException when the operation hides a path parameter, two visible
     *     inputs share a name and location, a published request body carries no matching redaction
     *     manifest or one that does not resolve in it, a published parameter or form-field schema holds
     *     {@code propertyNames}, or a published captured schema holds a refused construct
     */
    static Plan check(
            String subject,
            SchemaEmbedder embedder,
            OperationPublication operation,
            OperationFacts facts,
            AssemblyContext context,
            DisclosureTally tally) {
        OperationDetail detail = operation.detail();
        if (detail == null) {
            return new Plan(operation.operationId(), List.of(), null, null);
        }
        String operationId = operation.operationId();
        OperationFacts known = facts == null ? NO_FACTS : facts;
        CapturedSchemas schemas = detail.schemas();
        HiddenInputs.refusePath(subject, operationId, detail.inputs());
        List<InputBinding> inputs = HiddenInputs.visible(detail.inputs());
        if (inputs.size() != detail.inputs().size()) {
            tally.hiddenInputOmitted();
        }
        checkDuplicates(subject, operationId, inputs);

        InputBinding bodyBinding = null;
        List<InputBinding> forms = new ArrayList<>();
        for (InputBinding binding : inputs) {
            if (binding.origin() == InputBinding.Origin.BODY) {
                if (bodyBinding == null) {
                    bodyBinding = binding;
                }
            } else if (binding.location() == ParamLocation.FORM) {
                forms.add(binding);
            }
        }

        BodyPlan body = null;
        if (bodyBinding != null) {
            JsonObject captured = schemas == null ? null : schemas.body();
            SchemaEmbedder.CheckedSchema checked = null;
            if (captured != null) {
                RedactionManifest manifest =
                        ManifestVerifier.verify(subject, operationId, captured, schemas.bodyProvenance(), context);
                checked = embedder.check(InputDescription.body(operationId), captured);
                ReservedNameRedaction.redact(subject, operationId, manifest, checked.tree(), context, tally);
            }
            boolean required = detail.gateInstalled() && captured != null && rejectsNull(captured);
            body = new BodyPlan(mediaTypes(known.consumes(), DEFAULT_BODY_MEDIA_TYPE), checked, required);
        }

        List<ParameterPlan> parameters = new ArrayList<>();
        for (InputBinding.Origin origin : List.of(InputBinding.Origin.PARAMETER, InputBinding.Origin.COMPOSITE_FIELD)) {
            for (InputBinding binding : inputs) {
                if (binding.origin() == origin && binding.location() != ParamLocation.FORM) {
                    parameters.add(parameter(subject, embedder, operationId, schemas, binding));
                }
            }
        }

        FormPlan form = null;
        if (bodyBinding == null && !forms.isEmpty()) {
            List<PropertyPlan> properties = new ArrayList<>();
            for (InputBinding binding : forms) {
                properties.add(formProperty(subject, embedder, operationId, schemas, known, binding));
            }
            boolean filePart = false;
            for (InputBinding binding : forms) {
                filePart |= known.namedFileParts().contains(binding.name());
            }
            String defaultMediaType = filePart ? MULTIPART : URL_ENCODED;
            form = new FormPlan(mediaTypes(known.consumes(), defaultMediaType), properties);
        }
        return new Plan(operationId, parameters, body, form);
    }

    /**
     * Fails when two non-body inputs of an operation share a name and a location.
     */
    private static void checkDuplicates(String subject, String operationId, List<InputBinding> inputs) {
        Set<InputKey> seen = new HashSet<>();
        for (InputBinding binding : inputs) {
            if (binding.origin() == InputBinding.Origin.BODY || binding.location() == null || binding.name() == null) {
                continue;
            }
            if (!seen.add(new InputKey(binding.location(), binding.name()))) {
                throw new RestConfigurationException(subject + ": operation '" + operationId
                        + "' binds more than one input named '" + binding.name() + "' in " + in(binding.location())
                        + "; a document describes one parameter per name and location");
            }
        }
    }

    /** Plans one Parameter Object, checking its captured schema when it publishes one. */
    private static ParameterPlan parameter(
            String subject,
            SchemaEmbedder embedder,
            String operationId,
            CapturedSchemas schemas,
            InputBinding binding) {
        JsonObject captured = captured(schemas, binding);
        SchemaEmbedder.CheckedSchema checked = null;
        if (captured != null) {
            InputDescription input = InputDescription.parameter(operationId, binding.location(), binding.name());
            ParameterPropertyNames.refuse(subject, input, captured);
            checked = embedder.check(input, captured);
        }
        return new ParameterPlan(
                binding.name(),
                in(binding.location()),
                description(binding.annotations()),
                binding.requiredness() == InputBinding.Requiredness.REQUIRED,
                checked,
                checked == null ? unenforced(binding) : null);
    }

    /** Plans one form-body property, checking its captured schema when it publishes one. */
    private static PropertyPlan formProperty(
            String subject,
            SchemaEmbedder embedder,
            String operationId,
            CapturedSchemas schemas,
            OperationFacts facts,
            InputBinding binding) {
        if (facts.namedFileParts().contains(binding.name())) {
            return new PropertyPlan(binding.name(), null, NODES.objectNode());
        }
        JsonObject captured = captured(schemas, binding);
        if (captured == null) {
            return new PropertyPlan(binding.name(), null, unenforced(binding));
        }
        InputDescription input = InputDescription.formField(operationId, binding.name());
        ParameterPropertyNames.refuse(subject, input, captured);
        return new PropertyPlan(binding.name(), embedder.check(input, captured), null);
    }

    /**
     * Returns the schema captured for a binding's location and name, or {@code null} for a composite
     * field, which is never enforced, and for an input with no captured schema.
     */
    private static JsonObject captured(CapturedSchemas schemas, InputBinding binding) {
        if (binding.origin() == InputBinding.Origin.COMPOSITE_FIELD
                || schemas == null
                || binding.location() == null
                || binding.name() == null) {
            return null;
        }
        return schemas.parameters().get(new InputKey(binding.location(), binding.name()));
    }

    /**
     * Builds the schema of an unenforced input: its raw default value when it declares one, else the
     * empty schema.
     */
    private static ObjectNode unenforced(InputBinding binding) {
        ObjectNode schema = NODES.objectNode();
        if (binding.defaultValue() != null) {
            schema.put("default", binding.defaultValue());
        }
        return schema;
    }

    /**
     * Returns the description of the first {@link Parameter} annotation of an input, or {@code null}
     * when it has none or a blank one.
     */
    private static String description(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Parameter parameter) {
                String description = parameter.description();
                return description == null || description.isBlank() ? null : description;
            }
        }
        return null;
    }

    /** Returns the consumed media types, or the one default when none is declared. */
    private static List<String> mediaTypes(List<String> consumes, String defaultMediaType) {
        return consumes.isEmpty() ? List.of(defaultMediaType) : consumes;
    }

    /** Returns a location as the lowercase {@code in} value of a Parameter Object. */
    private static String in(ParamLocation location) {
        return location.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds a request body holding one media type per entry, each with the schema: the schema itself
     * under the first media type and a copy under each further one.
     */
    private static ObjectNode requestBody(List<String> mediaTypes, JsonNode schema) {
        ObjectNode requestBody = NODES.objectNode();
        ObjectNode content = requestBody.putObject("content");
        boolean first = true;
        for (String mediaType : mediaTypes) {
            content.putObject(mediaType).set("schema", first ? schema : schema.deepCopy());
            first = false;
        }
        return requestBody;
    }

    /**
     * Evaluates whether a captured body schema rejects an absent body, compiled as the gate compiles
     * it. The engine writes into the object it compiles, so it compiles a copy.
     */
    private static boolean rejectsNull(JsonObject body) {
        Boolean valid = Validator.create(JsonSchema.of(body.copy()), GATE_OPTIONS)
                .validate((Object) null)
                .getValid();
        return !Boolean.TRUE.equals(valid);
    }

    /**
     * The checked plan of one Operation Object.
     *
     * @param operationId the runtime operation id
     * @param parameters the Parameter Objects, in published order
     * @param body the request body of the body binding, or {@code null}
     * @param form the form request body, or {@code null}
     */
    record Plan(String operationId, List<ParameterPlan> parameters, BodyPlan body, FormPlan form) {

        /**
         * Writes the Operation Object, publishing each checked schema once.
         *
         * @param embedder the schema embedder that checked the schemas
         * @return the Operation Object with {@code operationId}, {@code parameters} when there are
         *     any, and {@code requestBody} when there is one, in that order
         * @throws RestConfigurationException when a component key is already taken in the document
         */
        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode node = NODES.objectNode();
            node.put("operationId", operationId);
            if (!parameters.isEmpty()) {
                ArrayNode array = node.putArray("parameters");
                for (ParameterPlan parameter : parameters) {
                    array.add(parameter.publish(embedder));
                }
            }
            if (body != null) {
                node.set("requestBody", body.publish(embedder));
            } else if (form != null) {
                node.set("requestBody", form.publish(embedder));
            }
            return node;
        }
    }

    /**
     * One planned Parameter Object.
     *
     * @param name the parameter name
     * @param in the lowercase location
     * @param description the description, or {@code null}
     * @param required whether the parameter is certainly required
     * @param checked the checked captured schema, or {@code null} for an unenforced input
     * @param unenforced the unenforced schema, or {@code null} when a captured schema is published
     */
    private record ParameterPlan(
            String name,
            String in,
            String description,
            boolean required,
            SchemaEmbedder.CheckedSchema checked,
            ObjectNode unenforced) {

        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode node = NODES.objectNode();
            node.put("name", name);
            node.put("in", in);
            if (description != null) {
                node.put("description", description);
            }
            if (required) {
                node.put("required", true);
            }
            node.set("schema", checked != null ? embedder.publish(checked) : unenforced);
            return node;
        }
    }

    /**
     * The planned request body of a body binding.
     *
     * @param mediaTypes the media types, in order
     * @param checked the checked captured body, or {@code null} when none was captured
     * @param required whether the body is required
     */
    private record BodyPlan(List<String> mediaTypes, SchemaEmbedder.CheckedSchema checked, boolean required) {

        ObjectNode publish(SchemaEmbedder embedder) {
            JsonNode schema = checked != null ? embedder.publish(checked) : NODES.objectNode();
            ObjectNode requestBody = requestBody(mediaTypes, schema);
            if (required) {
                requestBody.put("required", true);
            }
            return requestBody;
        }
    }

    /**
     * The planned request body of an operation's form bindings.
     *
     * @param mediaTypes the media types, in order
     * @param properties the properties, in inventory order
     */
    private record FormPlan(List<String> mediaTypes, List<PropertyPlan> properties) {

        ObjectNode publish(SchemaEmbedder embedder) {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", "object");
            ObjectNode members = schema.putObject("properties");
            for (PropertyPlan property : properties) {
                members.set(property.name(), property.publish(embedder));
            }
            return requestBody(mediaTypes, schema);
        }
    }

    /**
     * One planned form-body property.
     *
     * @param name the form field name
     * @param checked the checked captured schema, or {@code null}
     * @param fixed the schema published as is when nothing captured is published
     */
    private record PropertyPlan(String name, SchemaEmbedder.CheckedSchema checked, ObjectNode fixed) {

        JsonNode publish(SchemaEmbedder embedder) {
            return checked != null ? embedder.publish(checked) : fixed;
        }
    }
}
