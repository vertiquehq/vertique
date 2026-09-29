// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;

/**
 * INTERNAL: the schema and validation detail captured for one operation, present on {@link
 * OperationPublication#detail()} only for a mount a sink wanted detail for. Public only for
 * cross-module use by sibling framework modules; outside the maturity promise and not an
 * application contract.
 *
 * @param descriptor    the operation's descriptor, valid for annotation reads only during {@link
 *                      OperationPublicationSink#mountBuilt}; not retained past that call
 * @param profileId     the resolved JSON mapper profile id for this operation
 * @param schemas       the detached schema copies captured for this operation
 * @param gateInstalled whether a request-validation gate was installed for this operation
 */
public record OperationDetail(
        JaxRsOperationDescriptor descriptor, String profileId, CapturedSchemas schemas, boolean gateInstalled) {}
