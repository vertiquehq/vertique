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
 * {@link OperationSchemaSource} and consumed by the web-validation gate; they carry only
 * vertx-json-schema JSON, never any victools or rest-jaxrs internal type, keeping this model on the
 * SPI seam.
 *
 * <p>Construct instances with {@link #builder()}.
 */
public final class OperationSchemas {

    private final JsonObject bodySchema;
    private final Map<ParamKey, JsonObject> parameterSchemas;

    private OperationSchemas(JsonObject bodySchema, Map<ParamKey, JsonObject> parameterSchemas) {
        this.bodySchema = bodySchema;
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
        return new OperationSchemas(null, Map.of());
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
     * Mutable builder accumulating an optional body schema and per-parameter schemas before producing
     * an immutable {@link OperationSchemas}.
     */
    public static final class Builder {

        private JsonObject bodySchema;
        private final Map<ParamKey, JsonObject> parameterSchemas = new HashMap<>();

        private Builder() {}

        /**
         * Sets the request-body schema.
         *
         * @param schema the body schema as vertx-json-schema JSON; {@code null} clears any prior value
         * @return this builder
         */
        public Builder bodySchema(JsonObject schema) {
            this.bodySchema = schema;
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
            return new OperationSchemas(bodySchema, parameterSchemas);
        }
    }
}
