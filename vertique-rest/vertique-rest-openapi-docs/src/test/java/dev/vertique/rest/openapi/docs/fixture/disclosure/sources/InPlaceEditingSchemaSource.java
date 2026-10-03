// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * A decorating source that edits the delegate's body schema in place: it appends {@link
 * #ADDED_ELEMENT} to the body's {@code allOf}, creating {@code allOf} when absent, on the very object
 * the delegate returned, and returns the delegate's {@link OperationSchemas} instance itself, so the
 * delegate's redaction manifest stays attached to a body it no longer matches. The added element
 * accepts every value. Parameter schemas are returned unchanged.
 */
public final class InPlaceEditingSchemaSource implements OperationSchemaSource {

    /** The element appended to every body's {@code allOf}: a schema every instance satisfies. */
    public static final JsonObject ADDED_ELEMENT = new JsonObject().put("minProperties", 0);

    private final OperationSchemaSource delegate;

    /** Wraps a new canonical {@link AnnotationSchemaSource}, which consults no Bean Validation. */
    public InPlaceEditingSchemaSource() {
        this(new AnnotationSchemaSource());
    }

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose body is edited in place, normally the canonical source
     */
    public InPlaceEditingSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas returned = delegate.schemasFor(op, profile);
        returned.bodySchema().ifPresent(body -> {
            JsonArray allOf = body.getJsonArray("allOf");
            if (allOf == null) {
                allOf = new JsonArray();
                body.put("allOf", allOf);
            }
            allOf.add(ADDED_ELEMENT.copy());
        });
        return returned;
    }
}
