// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;

/**
 * Seam that produces the validation {@link OperationSchemas} for a REST operation from its
 * {@link JaxRsOperationDescriptor} and the effective JSON profile the registrar resolved for it.
 *
 * <p>This SPI consumes only the public JAX-RS operation descriptor (parameter and body descriptors,
 * annotations) and the public {@link JsonMapperProfile}, and emits vertx-json-schema JSON. It
 * deliberately does <strong>not</strong> reference victools or any rest-jaxrs internal route-dispatch
 * metadata type, so that rest-jaxrs carries no schema-generator dependency — the seam is what lets the
 * runtime victools producer live in {@code vertique-rest-validation}.
 *
 * <p>The runtime, annotation-driven producer ({@code AnnotationSchemaSource}, using victools) ships in
 * the {@code vertique-rest-validation} module. A build-time codegen producer that synthesizes schemas
 * at compile time is deferred (NG9 in PRD-REST-017).
 */
@FunctionalInterface
public interface OperationSchemaSource {

    /**
     * Produces the parameter and body schemas for the given operation under the effective JSON
     * profile the registrar resolved for it. The profile is the one whose mapper parses the
     * operation's body; a source that synthesizes a body schema uses it. A source that ignores the
     * parameter guarantees that the schemas it returns already match that profile's wire shape;
     * the framework cannot check this.
     *
     * <p>A source that cannot synthesize a body schema for the profile throws an unchecked
     * exception; the runtime producer in {@code vertique-rest-validation} throws
     * {@code dev.vertique.rest.core.RestConfigurationException} with the generator failure as
     * cause. Router construction then fails and the mount is never installed.
     *
     * @param op      the JAX-RS operation descriptor whose parameters and body are introspected
     * @param profile the effective, registry-resolved profile for this operation; never {@code null}
     * @return the operation's schemas; never {@code null} (an operation with no body and no
     *     parameters yields an empty {@link OperationSchemas})
     */
    OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile);
}
