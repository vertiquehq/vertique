// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import java.util.List;
import java.util.Optional;

/**
 * Richer JAX-RS/schema view of a REST operation, extending the transport-neutral
 * {@link RestOperationDescriptor} base with the members that reference rest-jaxrs record types.
 *
 * <p>This descriptor is consumed by the rest-jaxrs validation and schema-synthesis seams. It adds
 * only parameter, body, and file-part accessors so that rest-core never needs to depend on
 * rest-jaxrs; the identity, security, media-type, and annotation accessors are inherited from the
 * base.
 */
public interface JaxRsOperationDescriptor extends RestOperationDescriptor {

    /**
     * Returns the declared request parameters (path, query, header, cookie, form).
     *
     * @return the non-null, possibly empty list of parameter descriptors
     */
    List<ParamDescriptor> parameters();

    /**
     * Returns file-part constraint metadata for this operation. Named form-file parameters remain
     * present in {@link #parameters()}; this is an additional validation view.
     *
     * @return the non-null, possibly empty list of file-part descriptors
     */
    List<FilePartDescriptor> fileParts();

    /**
     * Returns the declared request body descriptor, when the operation declares one.
     *
     * @return the body descriptor, or {@link Optional#empty()} when the operation has no body
     */
    Optional<BodyDescriptor> body();
}
