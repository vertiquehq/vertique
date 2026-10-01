// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.sources;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import java.util.Objects;

/**
 * A decorating source that returns exactly what its delegate returns: the same {@link
 * OperationSchemas} instance, body schema, manifest, and parameter schemas. Every body it returns
 * verifies against its manifest when the delegate is the canonical source; it is still a source other
 * than the canonical one.
 */
public final class PassThroughSchemaSource implements OperationSchemaSource {

    private final OperationSchemaSource delegate;

    /** Wraps a new canonical {@link AnnotationSchemaSource}, which consults no Bean Validation. */
    public PassThroughSchemaSource() {
        this(new AnnotationSchemaSource());
    }

    /**
     * Wraps a delegate.
     *
     * @param delegate the source whose schemas are returned, normally the canonical source
     */
    public PassThroughSchemaSource(OperationSchemaSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        return delegate.schemasFor(op, profile);
    }
}
