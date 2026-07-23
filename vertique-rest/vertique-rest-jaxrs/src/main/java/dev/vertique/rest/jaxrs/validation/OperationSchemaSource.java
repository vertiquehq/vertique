// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;

/**
 * Seam that produces the validation {@link OperationSchemas} for a REST operation from its
 * {@link JaxRsOperationDescriptor}.
 *
 * <p>This SPI consumes only the public JAX-RS operation descriptor (parameter and body descriptors,
 * annotations) and emits vertx-json-schema JSON. It deliberately does <strong>not</strong> reference
 * victools or any rest-jaxrs internal route-dispatch metadata type, so that rest-jaxrs carries no
 * schema-generator dependency — the seam is what lets the runtime victools producer live in
 * {@code vertique-rest-validation}.
 *
 * <p>The runtime, annotation-driven producer ({@code AnnotationSchemaSource}, using victools) ships in
 * the {@code vertique-rest-validation} module. A build-time codegen producer that synthesizes schemas
 * at compile time is deferred (NG9 in PRD-REST-017).
 */
@FunctionalInterface
public interface OperationSchemaSource {

    /**
     * Produces the parameter and body schemas for the given operation.
     *
     * @param op the JAX-RS operation descriptor whose parameters and body are introspected
     * @return the operation's schemas; never {@code null} (an operation with no body and no parameters
     *     yields an empty {@link OperationSchemas})
     */
    OperationSchemas schemasFor(JaxRsOperationDescriptor op);
}
