// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * A decorating source that returns a different body schema with the delegate's redaction manifest
 * attached: a copy of the delegate's body whose {@code properties} is replaced by {@link
 * #REPLACED_PROPERTIES}, carried with the delegate's provenance through the two-argument {@code
 * bodySchema(JsonObject, Object)}. The manifest therefore no longer matches the body it is attached
 * to. Parameter schemas are returned unchanged; an operation without a body schema is returned as the
 * delegate returned it.
 */
public final class ReplacingSchemaSource implements OperationSchemaSource {

    /** The {@code properties} value of every replaced body. */
    public static final JsonObject REPLACED_PROPERTIES =
            new JsonObject().put("other", new JsonObject().put("type", "string"));

    private final OperationSchemaSource delegate;

    /** Wraps a new canonical {@link AnnotationSchemaSource}, which consults no Bean Validation. */
    public ReplacingSchemaSource() {
        this(new AnnotationSchemaSource());
    }

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose body is replaced, normally the canonical source
     */
    public ReplacingSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the delegate's schemas with a replaced body carrying the delegate's provenance.
     *
     * @param op      the operation descriptor
     * @param profile the operation's profile
     * @return the schemas
     * @throws IllegalStateException if the delegate returned a body without provenance, so the
     *     replaced body could not carry the delegate's manifest
     */
    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas returned = delegate.schemasFor(op, profile);
        JsonObject body = returned.bodySchema().orElse(null);
        if (body == null) {
            return returned;
        }
        Object provenance = returned.bodySchemaProvenance(Object.class)
                .orElseThrow(() -> new IllegalStateException("the delegate returned the body of operation '"
                        + op.operationId() + "' without provenance, so there is no manifest to carry"));
        JsonObject replaced = body.copy().put("properties", REPLACED_PROPERTIES.copy());
        return returned.toBuilder().bodySchema(replaced, provenance).build();
    }
}
