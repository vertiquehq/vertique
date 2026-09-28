// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable per-operation collection of JSON schemas: an optional request-body schema plus a schema
 * for each declared request parameter, each expressed as a vertx-json-schema {@link JsonObject}.
 *
 * <p>Parameter schemas are keyed by the pair {@code (location, name)} so that a query parameter and a
 * path parameter sharing a name remain distinct. Instances are produced by an
 * {@link OperationSchemaSource} and consumed by the web-validation gate; their schemas are only
 * vertx-json-schema JSON, never any victools or rest-jaxrs internal type, keeping this model on the
 * SPI seam.
 *
 * <p>Besides its JSON, the body schema may carry an INTERNAL, opaque <em>provenance</em> object that
 * the framework module which generated the schema attaches through
 * {@link Builder#bodySchema(JsonObject, Object)} and reads back through
 * {@link #bodySchemaProvenance(Class)}. This module never inspects it: it stores the object as given
 * and returns it by reference. Parameter schemas carry no provenance, and the gate never reads it.
 *
 * <p>Construct instances with {@link #builder()}, or derive a changed copy with {@link #toBuilder()}.
 */
public final class OperationSchemas {

    private final JsonObject bodySchema;
    private final Object bodySchemaProvenance;
    private final Map<ParamKey, JsonObject> parameterSchemas;

    private OperationSchemas(
            JsonObject bodySchema, Object bodySchemaProvenance, Map<ParamKey, JsonObject> parameterSchemas) {
        this.bodySchema = bodySchema;
        this.bodySchemaProvenance = bodySchemaProvenance;
        this.parameterSchemas = Map.copyOf(parameterSchemas);
    }

    /**
     * Returns the request-body schema, when the operation declares one.
     *
     * @return the body schema, or {@link Optional#empty()} when the operation has no request body
     */
    public Optional<JsonObject> bodySchema() {
        return Optional.ofNullable(bodySchema);
    }

    /**
     * Returns the body schema's provenance when it is an instance of {@code type}.
     *
     * <p><strong>INTERNAL.</strong> Public only for cross-module use by framework modules and outside
     * this module's maturity promise: it may change or disappear without notice. The provenance is
     * opaque to this module, which returns the very object the schema source attached. A body set
     * with the one-argument {@link Builder#bodySchema(JsonObject)} — including one that replaces a
     * body copied by {@link #toBuilder()} — has no provenance.
     *
     * @param type the class the caller expects the provenance to be an instance of
     * @param <T>  the expected provenance type
     * @return the provenance, or {@link Optional#empty()} when there is none or it is not an instance
     *     of {@code type}
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public <T> Optional<T> bodySchemaProvenance(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.isInstance(bodySchemaProvenance) ? Optional.of(type.cast(bodySchemaProvenance)) : Optional.empty();
    }

    /**
     * Returns the schema for a declared parameter identified by its location and name.
     *
     * @param location the parameter location (path, query, header, cookie, form)
     * @param name     the declared parameter name
     * @return the parameter schema, or {@link Optional#empty()} when no schema is registered for that
     *     {@code (location, name)} pair
     */
    public Optional<JsonObject> parameterSchema(ParamLocation location, String name) {
        return Optional.ofNullable(parameterSchemas.get(new ParamKey(location, name)));
    }

    /**
     * Returns a new {@link Builder} holding this instance's body schema, the body's provenance, and
     * every parameter schema.
     *
     * <p><strong>INTERNAL.</strong> Public only for cross-module use by framework modules and outside
     * this module's maturity promise: it may change or disappear without notice. Building the returned
     * builder unchanged gives an instance with equal content and the same provenance object; no call
     * on the builder ever changes this instance. The body and parameter schemas are shared by
     * reference, not copied. Replacing the body through the builder's one-argument
     * {@link Builder#bodySchema(JsonObject)} drops the copied provenance, so a decorating source that
     * replaces the body carries none.
     *
     * @return a fresh builder seeded with this instance's schemas and provenance
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.bodySchema = bodySchema;
        builder.bodySchemaProvenance = bodySchemaProvenance;
        builder.parameterSchemas.putAll(parameterSchemas);
        return builder;
    }

    /**
     * Returns a new, empty {@link Builder}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns an {@link OperationSchemas} with no body schema and no parameter schemas. Used when no
     * {@link OperationSchemaSource} is installed, so a strategy that needs schemas receives an empty
     * collection rather than {@code null} (e.g. the {@code none} strategy, which ignores it anyway).
     *
     * @return an immutable empty schema collection
     */
    public static OperationSchemas empty() {
        return new OperationSchemas(null, null, Map.of());
    }

    /**
     * Composite key identifying a parameter schema by its location and declared name.
     *
     * @param location the parameter location
     * @param name     the declared parameter name
     */
    private record ParamKey(ParamLocation location, String name) {
        private ParamKey {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Mutable builder accumulating an optional body schema, the body's optional provenance, and
     * per-parameter schemas before producing an immutable {@link OperationSchemas}.
     */
    public static final class Builder {

        private JsonObject bodySchema;
        private Object bodySchemaProvenance;
        private final Map<ParamKey, JsonObject> parameterSchemas = new HashMap<>();

        private Builder() {}

        /**
         * Sets the request-body schema without provenance. Any provenance set by an earlier call, or
         * copied by {@link OperationSchemas#toBuilder()}, is dropped.
         *
         * @param schema the body schema as vertx-json-schema JSON; {@code null} clears any prior value
         * @return this builder
         */
        public Builder bodySchema(JsonObject schema) {
            this.bodySchema = schema;
            this.bodySchemaProvenance = null;
            return this;
        }

        /**
         * Sets the request-body schema together with its provenance.
         *
         * <p><strong>INTERNAL.</strong> Public only for cross-module use by framework modules and
         * outside this module's maturity promise: it may change or disappear without notice. The
         * provenance is opaque to this module, which stores it as given and never inspects it; a later
         * one-argument {@link #bodySchema(JsonObject)} call drops it. To clear the body, pass
         * {@code null} to the one-argument form.
         *
         * @param schema     the body schema as vertx-json-schema JSON; never {@code null}
         * @param provenance the opaque provenance the schema's producer binds to it; never {@code null}
         * @return this builder
         * @throws NullPointerException if {@code schema} or {@code provenance} is {@code null}, naming
         *     the argument; the builder is then left unchanged
         */
        public Builder bodySchema(JsonObject schema, Object provenance) {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(provenance, "provenance");
            this.bodySchema = schema;
            this.bodySchemaProvenance = provenance;
            return this;
        }

        /**
         * Registers the schema for a declared parameter.
         *
         * @param location the parameter location
         * @param name     the declared parameter name
         * @param schema   the parameter schema as vertx-json-schema JSON
         * @return this builder
         */
        public Builder parameterSchema(ParamLocation location, String name, JsonObject schema) {
            this.parameterSchemas.put(new ParamKey(location, name), schema);
            return this;
        }

        /**
         * Builds the immutable {@link OperationSchemas} from the accumulated schemas.
         *
         * @return the immutable schema collection
         */
        public OperationSchemas build() {
            return new OperationSchemas(bodySchema, bodySchemaProvenance, parameterSchemas);
        }
    }
}
