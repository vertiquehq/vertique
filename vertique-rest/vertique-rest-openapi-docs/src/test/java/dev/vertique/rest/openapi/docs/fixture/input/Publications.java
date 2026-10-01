// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies.GeneratedBody;
import dev.vertique.rest.validation.WebValidationStrategy;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A fluent builder for synthetic, already detached application-mount publications, the shape the
 * documentation sink hands to the document assembler.
 *
 * <pre>{@code
 * Publications.Built built = Publications.mount("/api/public/*")
 *         .application("public", UnitDocumentedApi.class)
 *         .operation("GET", "/catalog/{id}", "getItem")
 *             .param(ParamLocation.PATH, "id", Requiredness.REQUIRED).schema(new JsonObject().put("type", "string"))
 *             .param(ParamLocation.QUERY, "q", Requiredness.UNKNOWN).description("Search text")
 *         .operation("POST", "/catalog", "createItem")
 *             .consumes("application/json")
 *             .body(GeneratedBodies.describe(Item.class))
 *         .build();
 * }</pre>
 *
 * <p>The inventory of each operation lists its inputs in call order. {@link OperationBuilder#schema},
 * {@link OperationBuilder#defaultValue} and {@link OperationBuilder#description} apply to the input
 * added last. Each input's {@code schemaEnforced} flag follows the inventory's own rule, computed at
 * {@link #build()}: a method parameter (form fields included) is enforced exactly when the gate is
 * installed and a schema is captured under its location and name; the body exactly when the gate is
 * installed and a body schema is captured; a composite field never, even with a captured schema.
 *
 * <p>The built publication is detached as the documentation sink detaches it: every operation has
 * detail, the detail's descriptor is {@code null}, and the response shape is kept (here a
 * {@code void} method with no produced media type). The consumed media types and named file parts
 * the sink takes from the descriptor before detaching are returned beside the publication, per
 * operation id, in {@link Built}.
 *
 * <p>Captured schemas are the very objects the caller passed, not copies, so a test can tell whether
 * assembly wrote into them (see {@link Snapshots}). Defaults: strategy {@code web-validation}, profile
 * {@code vertique}, gate installed, no consumed media type, the effective policy {@code None}, no
 * security requirement, no required action, the route value equal to the template.
 */
public final class Publications {

    /** The default profile id of every operation. */
    public static final String DEFAULT_PROFILE_ID = GeneratedBodies.DEFAULT_PROFILE;

    private final String mountPath;
    private @Nullable String applicationName;
    private @Nullable Class<?> declaringType;
    private String strategyId = WebValidationStrategy.ID;
    private final List<OperationBuilder> operations = new ArrayList<>();

    private Publications(String mountPath) {
        this.mountPath = Objects.requireNonNull(mountPath, "mountPath");
    }

    /**
     * Starts a mount publication.
     *
     * @param mountPath the mount path as registered, for example {@code "/api/public/*"} or {@code "/*"}
     * @return a new mount builder
     */
    public static Publications mount(String mountPath) {
        return new Publications(mountPath);
    }

    /**
     * Names the mount's declared application.
     *
     * @param name          the application name, which also names its document
     * @param declaringType the declaring interface, for example {@link UnitDocumentedApi}
     * @return this builder
     */
    public Publications application(String name, Class<?> declaringType) {
        this.applicationName = Objects.requireNonNull(name, "name");
        this.declaringType = Objects.requireNonNull(declaringType, "declaringType");
        return this;
    }

    /**
     * Sets the configured request-validation strategy id; {@code web-validation} by default.
     *
     * @param strategyId the strategy id
     * @return this builder
     */
    public Publications strategy(String strategyId) {
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId");
        return this;
    }

    /**
     * Adds an operation, after every operation added before.
     *
     * @param httpMethod  the HTTP method, for example {@code "GET"}
     * @param template    the declared JAX-RS path template, relative to the mount
     * @param operationId the operation id
     * @return the new operation's builder
     */
    public OperationBuilder operation(String httpMethod, String template, String operationId) {
        OperationBuilder operation = new OperationBuilder(this, httpMethod, template, operationId);
        operations.add(operation);
        return operation;
    }

    /**
     * Builds the publication and the descriptor facts beside it.
     *
     * @return the built publication
     * @throws IllegalStateException if no application was named
     */
    public Built build() {
        if (applicationName == null || declaringType == null) {
            throw new IllegalStateException("a synthetic publication must name its application");
        }
        List<OperationPublication> built = new ArrayList<>();
        Map<String, List<String>> consumes = new LinkedHashMap<>();
        Map<String, List<String>> namedFileParts = new LinkedHashMap<>();
        for (OperationBuilder operation : operations) {
            built.add(operation.publication());
            consumes.put(operation.operationId, List.copyOf(operation.consumes));
            namedFileParts.put(operation.operationId, List.copyOf(operation.namedFileParts));
        }
        MountPublication publication = new MountPublication(
                mountPath, applicationName + "-mount", applicationName, declaringType, strategyId, built);
        return new Built(
                publication, Collections.unmodifiableMap(consumes), Collections.unmodifiableMap(namedFileParts));
    }

    /**
     * A built synthetic publication with the facts the documentation sink takes from each operation's
     * descriptor before detaching it.
     *
     * @param publication    the detached mount publication
     * @param consumes       per operation id, the consumed media types, in declaration order
     * @param namedFileParts per operation id, the names of the named file parts, in declaration order
     */
    public record Built(
            MountPublication publication,
            Map<String, List<String>> consumes,
            Map<String, List<String>> namedFileParts) {}

    /** Builds one operation of a mount; every mount-level call continues on the enclosing builder. */
    public static final class OperationBuilder {

        private final Publications mount;
        private final String httpMethod;
        private final String template;
        private final String operationId;
        private String vertxRouteValue;
        private boolean vertxRouteIsRegex;
        private String profileId = DEFAULT_PROFILE_ID;
        private boolean gateInstalled = true;
        private final List<String> consumes = new ArrayList<>();
        private final List<String> namedFileParts = new ArrayList<>();
        private final List<Input> inputs = new ArrayList<>();
        private @Nullable JsonObject bodySchema;
        private @Nullable Object bodyProvenance;
        private int nextParameterIndex;
        private @Nullable Integer currentCompositeIndex;

        private OperationBuilder(Publications mount, String httpMethod, String template, String operationId) {
            this.mount = mount;
            this.httpMethod = Objects.requireNonNull(httpMethod, "httpMethod");
            this.template = Objects.requireNonNull(template, "template");
            this.operationId = Objects.requireNonNull(operationId, "operationId");
            this.vertxRouteValue = template;
        }

        /**
         * Adds consumed media types, after those added before.
         *
         * @param mediaTypes the media types, for example {@code "application/json"}
         * @return this builder
         */
        public OperationBuilder consumes(String... mediaTypes) {
            consumes.addAll(List.of(mediaTypes));
            return this;
        }

        /**
         * Sets whether a request-validation gate was installed; {@code true} by default.
         *
         * @param installed whether the gate was installed
         * @return this builder
         */
        public OperationBuilder gateInstalled(boolean installed) {
            this.gateInstalled = installed;
            return this;
        }

        /**
         * Sets the operation's resolved profile id; {@code vertique} by default.
         *
         * @param id the profile id
         * @return this builder
         */
        public OperationBuilder profileId(String id) {
            this.profileId = Objects.requireNonNull(id, "id");
            return this;
        }

        /**
         * Sets the route value as registered with the router; the template, not a regex, by default.
         *
         * @param value the route value
         * @param regex whether the value was registered as a regex route
         * @return this builder
         */
        public OperationBuilder vertxRoute(String value, boolean regex) {
            this.vertxRouteValue = Objects.requireNonNull(value, "value");
            this.vertxRouteIsRegex = regex;
            return this;
        }

        /**
         * Adds the body input and captures a generated body schema with its manifest as provenance.
         *
         * @param body the generated body
         * @return this builder
         */
        public OperationBuilder body(GeneratedBody body) {
            return bodySchema(body.schema(), body.manifest());
        }

        /**
         * Adds the body input and captures the given body schema.
         *
         * @param schema   the captured body schema, kept by reference
         * @param manifest the body's provenance, or {@code null} for none
         * @return this builder
         */
        public OperationBuilder bodySchema(JsonObject schema, @Nullable RedactionManifest manifest) {
            Objects.requireNonNull(schema, "schema");
            addBody();
            this.bodySchema = schema;
            this.bodyProvenance = manifest;
            return this;
        }

        /**
         * Adds the body input without capturing a body schema, so the body is not enforced.
         *
         * @return this builder
         */
        public OperationBuilder bodyBindingWithoutSchema() {
            addBody();
            return this;
        }

        /**
         * Adds a bound method parameter.
         *
         * @param location     the parameter's location
         * @param name         the bound name
         * @param requiredness the parameter's requiredness
         * @return this builder
         */
        public OperationBuilder param(ParamLocation location, String name, Requiredness requiredness) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(requiredness, "requiredness");
            inputs.add(new Input(Origin.PARAMETER, location, name, requiredness, nextParameterIndex++));
            currentCompositeIndex = null;
            return this;
        }

        /**
         * Adds a form field: a bound method parameter at the {@code FORM} location whose requiredness
         * is {@code NOT_REQUIRED}.
         *
         * @param name the field name
         * @return this builder
         */
        public OperationBuilder formField(String name) {
            return formField(name, Requiredness.NOT_REQUIRED);
        }

        /**
         * Adds a form field: a bound method parameter at the {@code FORM} location.
         *
         * @param name         the field name
         * @param requiredness the field's requiredness
         * @return this builder
         */
        public OperationBuilder formField(String name, Requiredness requiredness) {
            return param(ParamLocation.FORM, name, requiredness);
        }

        /**
         * Adds a named file part: a form field whose name the descriptor also lists as a named file
         * part.
         *
         * @param name the part name
         * @return this builder
         */
        public OperationBuilder namedFilePart(String name) {
            formField(name);
            namedFileParts.add(name);
            return this;
        }

        /**
         * Adds a field of a {@code @BeanParam} or {@code @RequestParams} composite. Consecutive
         * composite fields belong to one composite parameter and share its parameter index.
         *
         * @param location     the field's location
         * @param name         the bound name
         * @param requiredness the field's requiredness
         * @return this builder
         */
        public OperationBuilder compositeField(ParamLocation location, String name, Requiredness requiredness) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(requiredness, "requiredness");
            if (currentCompositeIndex == null) {
                currentCompositeIndex = nextParameterIndex++;
            }
            inputs.add(new Input(Origin.COMPOSITE_FIELD, location, name, requiredness, currentCompositeIndex));
            return this;
        }

        /**
         * Captures a schema under the last input's location and name. A composite field's captured
         * schema stays unenforced, as the inventory reports it.
         *
         * @param schema the captured schema, kept by reference
         * @return this builder
         * @throws IllegalStateException if the last input is the body or no input was added
         */
        public OperationBuilder schema(JsonObject schema) {
            last().schema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        /**
         * Sets the last input's {@code @DefaultValue}.
         *
         * @param value the default value
         * @return this builder
         * @throws IllegalStateException if the last input is the body or no input was added
         */
        public OperationBuilder defaultValue(String value) {
            last().defaultValue = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Gives the last input a {@code @Parameter} annotation carrying the description, as a
         * declared {@code @Parameter(description = ...)} appears in the inventory's annotation list.
         *
         * @param description the description, carried unchanged (blank text included)
         * @return this builder
         * @throws IllegalStateException if the last input is the body or no input was added
         */
        public OperationBuilder description(String description) {
            last().annotations.add(ParameterAnnotations.described(description));
            return this;
        }

        /**
         * Adds the next operation to the enclosing mount.
         *
         * @param httpMethod  the HTTP method
         * @param template    the declared JAX-RS path template
         * @param operationId the operation id
         * @return the new operation's builder
         */
        public OperationBuilder operation(String httpMethod, String template, String operationId) {
            return mount.operation(httpMethod, template, operationId);
        }

        /**
         * Builds the enclosing mount.
         *
         * @return the built publication
         */
        public Built build() {
            return mount.build();
        }

        private void addBody() {
            if (inputs.stream().anyMatch(input -> input.origin == Origin.BODY)) {
                throw new IllegalStateException("operation '" + operationId + "' already has a body");
            }
            inputs.add(new Input(Origin.BODY, null, null, Requiredness.UNKNOWN, nextParameterIndex++));
            currentCompositeIndex = null;
        }

        private Input last() {
            if (inputs.isEmpty() || inputs.get(inputs.size() - 1).origin == Origin.BODY) {
                throw new IllegalStateException("add a parameter or composite field before qualifying it");
            }
            return inputs.get(inputs.size() - 1);
        }

        private OperationPublication publication() {
            Map<InputKey, JsonObject> parameters = new LinkedHashMap<>();
            for (Input input : inputs) {
                if (input.schema != null) {
                    InputKey key = new InputKey(input.location, input.name);
                    if (parameters.containsKey(key)) {
                        throw new IllegalStateException("operation '" + operationId + "' captures two schemas for "
                                + input.location + " '" + input.name + "'");
                    }
                    parameters.put(key, input.schema);
                }
            }
            CapturedSchemas schemas =
                    new CapturedSchemas(bodySchema, bodySchema == null ? null : bodyProvenance, parameters);
            List<InputBinding> bindings = new ArrayList<>();
            for (Input input : inputs) {
                bindings.add(input.binding(gateInstalled, schemas));
            }
            ResponseShape response = new ResponseShape(void.class, Object.class, false, true, List.of(), profileId);
            OperationDetail detail = new OperationDetail(null, profileId, schemas, gateInstalled, bindings, response);
            return new OperationPublication(
                    operationId,
                    httpMethod,
                    template,
                    vertxRouteValue,
                    vertxRouteIsRegex,
                    new SecurityPolicy.None(),
                    List.of(),
                    false,
                    detail);
        }
    }

    /** One input under construction. */
    private static final class Input {

        private final Origin origin;
        private final @Nullable ParamLocation location;
        private final @Nullable String name;
        private final Requiredness requiredness;
        private final int methodParameterIndex;
        private @Nullable JsonObject schema;
        private @Nullable String defaultValue;
        private final List<Annotation> annotations = new ArrayList<>();

        private Input(
                Origin origin,
                @Nullable ParamLocation location,
                @Nullable String name,
                Requiredness requiredness,
                int methodParameterIndex) {
            this.origin = origin;
            this.location = location;
            this.name = name;
            this.requiredness = requiredness;
            this.methodParameterIndex = methodParameterIndex;
        }

        private InputBinding binding(boolean gateInstalled, CapturedSchemas schemas) {
            boolean enforced =
                    switch (origin) {
                        case PARAMETER ->
                            gateInstalled && schemas.parameters().containsKey(new InputKey(location, name));
                        case BODY -> gateInstalled && schemas.body() != null;
                        case COMPOSITE_FIELD -> false;
                    };
            return new InputBinding(
                    origin,
                    location,
                    name,
                    origin == Origin.BODY ? Object.class : String.class,
                    defaultValue,
                    requiredness,
                    false,
                    enforced,
                    annotations,
                    methodParameterIndex,
                    origin == Origin.COMPOSITE_FIELD ? Object.class : null);
        }
    }
}
