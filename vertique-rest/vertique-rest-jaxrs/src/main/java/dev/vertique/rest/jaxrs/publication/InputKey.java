// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import dev.vertique.rest.jaxrs.routing.ParamLocation;

/**
 * INTERNAL: identifies one descriptor parameter's captured schema within {@link
 * CapturedSchemas#parameters()}. Public only for cross-module use by sibling framework modules;
 * outside the maturity promise and not an application contract.
 *
 * @param location the parameter's location
 * @param name     the parameter's declared name
 */
public record InputKey(ParamLocation location, String name) {}
